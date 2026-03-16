package com.cubeos.meshsat.channel

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe registry of transport channel descriptors.
 * Port of meshsat/internal/channel/registry.go.
 */
class ChannelRegistry {

    private val lock = ReentrantReadWriteLock()
    private val channels = LinkedHashMap<String, ChannelDescriptor>()

    /**
     * Register a channel descriptor. Throws if ID already registered.
     */
    fun register(descriptor: ChannelDescriptor) {
        lock.write {
            require(descriptor.id !in channels) {
                "Channel \"${descriptor.id}\" already registered"
            }
            channels[descriptor.id] = descriptor
        }
    }

    /** Get the descriptor for a channel ID, or null if not found. */
    fun get(id: String): ChannelDescriptor? = lock.read { channels[id] }

    /** All registered channels in registration order. */
    fun list(): List<ChannelDescriptor> = lock.read { channels.values.toList() }

    /** All registered channel IDs in registration order. */
    fun ids(): List<String> = lock.read { channels.keys.toList() }

    /** True if the channel is a paid transport. */
    fun isPaid(id: String): Boolean = lock.read { channels[id]?.isPaid == true }

    /** True if the channel can be a rule destination. */
    fun canSend(id: String): Boolean = lock.read { channels[id]?.canSend == true }

    /** True if the channel can be a rule source. */
    fun canReceive(id: String): Boolean = lock.read { channels[id]?.canReceive == true }

    /** True if the channel supports raw binary payloads. */
    fun binaryCapable(id: String): Boolean = lock.read { channels[id]?.binaryCapable == true }
}
