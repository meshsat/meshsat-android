package net.meshsat.android.ble

import net.meshsat.android.ble.NodeOtaTransport.Outcome
import net.meshsat.android.ble.NodeOtaTransport.Reply
import net.meshsat.android.ble.NodeOtaTransport.Stage
import net.meshsat.android.ble.NodeOtaTransport.Step

/**
 * The OTA loader's line protocol once the link is up (MESHSAT-1672), apart from Bluetooth so every
 * way it can stop is tested (MESHSAT-1684). [run] reports through [onStage] how far it got before
 * each step that can fail, so a failure always names what the node is left with:
 * - nothing written yet: [Stage.NotCommanded];
 * - the command written (its write may have reached the loader even when the link says it did
 *   not): [Stage.EraseUncertain] until the loader answers;
 * - "ERR ..." before "ERASING": [Stage.Refused], the loader checks the command before it erases;
 * - "OK" after the erase: [Stage.Erased] for whatever happens next.
 */
internal object OtaProtocol {
    /** Put in the line stream when the link drops, so a reader stops waiting at once. */
    const val LINK_LOST = "\u0000link lost"

    interface Link {
        fun drain()

        /** Write [bytes]; false when the link could not take them. */
        suspend fun write(bytes: ByteArray, withResponse: Boolean): Boolean

        /** The updater's next line, [LINK_LOST] when the link dropped, null after [timeoutMs]. */
        suspend fun nextLine(timeoutMs: Long): String?
    }

    data class Timeouts(
        /** "ERASING", then "OK" once app0 is erased (several seconds for 3.3 MB). */
        val eraseMs: Long = 60_000L,
        val ackMs: Long = 10_000L,
        val verifyMs: Long = 30_000L,
    )

    suspend fun run(
        link: Link,
        bytes: ByteArray,
        sha256Hex: String,
        chunk: Int,
        timeouts: Timeouts,
        onStep: (Step) -> Unit,
        onStage: (Stage) -> Unit,
    ): Outcome {
        var stage = Stage.NotCommanded
        fun at(s: Stage) {
            stage = s
            onStage(s)
        }
        fun failed(reason: String) = Outcome.Failed(reason, stage)

        link.drain()
        // From here the loader may erase the firmware: the write can arrive even if it reports a failure.
        at(Stage.EraseUncertain)
        if (!link.write(NodeOtaTransport.otaCommand(bytes.size, sha256Hex).toByteArray(Charsets.US_ASCII), withResponse = true)) {
            return failed("The node's updater did not take the update command.")
        }
        var erasing = false
        while (true) {
            val line = link.nextLine(timeouts.eraseMs)
                ?: return failed(if (erasing) "The node's updater did not finish erasing." else "The node's updater did not answer the update command.")
            if (line == LINK_LOST) return failed("The link to the node's updater dropped while it was getting ready.")
            when (val r = NodeOtaTransport.parseReply(line)) {
                Reply.Erasing -> {
                    erasing = true
                    onStep(Step.Erasing)
                }
                Reply.Ok -> break
                is Reply.Error -> {
                    // The loader checks the command before it erases; an error after ERASING is a failed erase.
                    if (!erasing) at(Stage.Refused)
                    return failed("The node refused the update: ${r.text}")
                }
                else -> {}
            }
        }
        at(Stage.Erased)

        var sent = 0
        val total = bytes.size
        var lastPercent = -1
        while (sent < total) {
            val end = minOf(sent + chunk, total)
            if (!link.write(bytes.copyOfRange(sent, end), withResponse = false)) {
                return failed("A piece of the firmware could not be sent at byte $sent.")
            }
            val last = end == total
            if (last) onStep(Step.Verifying)
            val line = link.nextLine(if (last) timeouts.verifyMs else timeouts.ackMs)
                ?: return failed("The node's updater stopped answering at byte $end.")
            if (line == LINK_LOST) return failed("The link to the node's updater dropped at byte $end.")
            when (val r = NodeOtaTransport.parseReply(line)) {
                Reply.Ack -> if (last) return failed("The node's updater wanted more than the whole image.")
                Reply.Ok -> if (!last) return failed("The node's updater ended the transfer early at byte $end.")
                is Reply.Error -> return failed("The node's updater refused the firmware: ${r.text}")
                else -> return failed("The node's updater answered '$line' at byte $end.")
            }
            sent = end
            val percent = (sent * 100L / total).toInt()
            if (percent != lastPercent) {
                lastPercent = percent
                onStep(Step.Sending(sent, total))
            }
        }
        return Outcome.Done
    }
}
