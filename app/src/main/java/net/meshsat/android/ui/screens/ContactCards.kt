package net.meshsat.android.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.meshsat.android.data.AppDatabase
import net.meshsat.android.data.ContactEntity
import net.meshsat.android.pair.ContactQR
import net.meshsat.android.service.GatewayService
import net.meshsat.android.ui.theme.MeshSatSurface
import net.meshsat.android.ui.theme.MeshSatTextMuted
import java.util.Base64

/**
 * Handing a card over, face to face (MESHSAT-566, MESHSAT-575).
 *
 * One person shows "My card", the other scans it, and both read the same fingerprint off their
 * screens. The signature proves the card was made by the key it carries and has not been altered;
 * the fingerprint, read aloud, is what ties that key to the person standing there. A card that
 * arrived any other way is kept as IMPORTED, because nothing says who passed it on.
 */
@Composable
fun ContactCardsSection() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val db = remember { AppDatabase.getInstance(context) }
    val contacts by db.contactDao().observeAll().collectAsState(initial = emptyList())

    var showMyCard by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<ContactQR.Card, ContactQR.Trust>?>(null) }
    var showPaste by remember { mutableStateOf(false) }

    fun read(text: String, trust: ContactQR.Trust) {
        when (val r = ContactQR.decode(text)) {
            is ContactQR.Result.Ok -> pending = r.card to trust
            ContactQR.Result.BadSignature ->
                Toast.makeText(context, "That card has been altered since it was made. Not saved.", Toast.LENGTH_LONG).show()
            ContactQR.Result.Malformed ->
                Toast.makeText(context, "That is a MeshSat card, but a damaged one.", Toast.LENGTH_LONG).show()
            ContactQR.Result.NotACard ->
                Toast.makeText(context, "That is not a MeshSat contact card.", Toast.LENGTH_LONG).show()
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val scanned = com.journeyapps.barcodescanner.ScanContract().parseResult(result.resultCode, result.data)?.contents
        if (!scanned.isNullOrBlank()) read(scanned, ContactQR.Trust.SCANNED)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MeshSatSurface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("People you carry", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cards swapped face to face by QR code. Read the fingerprint aloud to each other: " +
                    "it is what says the card is theirs.",
                style = MaterialTheme.typography.bodySmall,
                color = MeshSatTextMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showMyCard = true }) { Text("My card") }
                OutlinedButton(onClick = {
                    try {
                        scanLauncher.launch(
                            com.journeyapps.barcodescanner.ScanContract().createIntent(
                                context,
                                com.journeyapps.barcodescanner.ScanOptions().apply {
                                    setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                                    setPrompt("Scan the other phone's card")
                                    setBeepEnabled(false)
                                    setOrientationLocked(true)
                                },
                            )
                        )
                    } catch (e: Exception) {
                        Toast.makeText(context, "No camera scanner: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Scan a card") }
                OutlinedButton(onClick = { showPaste = true }) { Text("Paste") }
            }

            if (contacts.isEmpty()) {
                Text(
                    "No cards yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MeshSatTextMuted,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else {
                contacts.forEach { c ->
                    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        Text(c.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            c.fingerprint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            buildString {
                                append(if (c.trust == ContactQR.Trust.SCANNED.name) "Scanned in person" else "Imported as text")
                                if (c.meshNodeId.isNotBlank()) append(" · mesh ${c.meshNodeId}")
                                if (c.bridgeId.isNotBlank()) append(" · ${c.bridgeId}")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MeshSatTextMuted,
                        )
                        TextButton(onClick = { scope.launch { db.contactDao().delete(c.fingerprint) } }) {
                            Text("Forget")
                        }
                    }
                }
            }
        }
    }

    if (showMyCard) MyCardDialog(onDismiss = { showMyCard = false })

    if (showPaste) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            containerColor = MeshSatSurface,
            title = { Text("Paste a card") },
            text = {
                Column {
                    Text(
                        "A card that did not come through the camera is kept as imported: the " +
                            "signature still holds, but nothing says who passed it on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("meshsat:contact:1:...") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPaste = false
                    read(text, ContactQR.Trust.IMPORTED)
                }) { Text("Read it") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
        )
    }

    pending?.let { (card, trust) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            containerColor = MeshSatSurface,
            title = { Text("Add ${card.name}?") },
            text = {
                Column {
                    Text(
                        "Check this fingerprint against the one on their screen. If it differs, " +
                            "the card is not theirs.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                    Text(
                        card.fingerprint,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    if (card.meshNodeId.isNotBlank()) Text("Mesh node ${card.meshNodeId}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    if (card.bridgeId.isNotBlank()) Text("Hub ${card.bridgeId}", style = MaterialTheme.typography.bodySmall, color = MeshSatTextMuted)
                    Text(
                        if (trust == ContactQR.Trust.SCANNED) "Scanned from a screen in front of you."
                        else "Imported as text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val c = ContactEntity(
                        fingerprint = card.fingerprint,
                        name = card.name,
                        signingPub = Base64.getEncoder().encodeToString(card.signingPubRaw),
                        meshNodeId = card.meshNodeId,
                        bridgeId = card.bridgeId,
                        trust = trust.name,
                        issuedAt = card.issuedAtSec,
                        addedAt = System.currentTimeMillis(),
                    )
                    pending = null
                    scope.launch { db.contactDao().upsert(c) }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

/** This phone's own card, as a QR code to hold up, with the fingerprint written under it. */
@Composable
private fun MyCardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val identity = GatewayService.routingIdentity
    val ble = GatewayService.meshtasticBle
    val myNum = ble?.myInfo?.value?.myNodeNum ?: 0L
    val myName = ble?.nodes?.value?.firstOrNull { it.nodeNum == myNum }?.longName.orEmpty()

    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }

    LaunchedEffect(identity, myName) {
        if (identity == null) return@LaunchedEffect
        val name = myName.ifBlank { "MeshSat phone" }.take(ContactQR.MAX_NAME)
        val card = ContactQR.Card(
            name = name,
            signingPubRaw = identity.signingPubRaw,
            meshNodeId = if (myNum != 0L) "!%08x".format(myNum) else "",
            bridgeId = "",
            issuedAtSec = System.currentTimeMillis() / 1000,
        )
        text = ContactQR.encode(card, identity)
        fingerprint = card.fingerprint
        qr = runCatching {
            com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(
                text, com.google.zxing.BarcodeFormat.QR_CODE, 720, 720,
            )
        }.getOrNull()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MeshSatSurface,
        title = { Text("My card") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (identity == null) {
                    Text(
                        "The gateway has not started yet, so this phone has no key to sign a card with.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "This phone's contact card as a QR code",
                            modifier = Modifier.size(260.dp)
                                .background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(8.dp))
                                .padding(8.dp),
                        )
                    }
                    Text(
                        fingerprint,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        "Read this out to whoever scans it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MeshSatTextMuted,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (text.isNotBlank()) {
                TextButton(onClick = {
                    val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("MeshSat card", text))
                    Toast.makeText(context, "Card copied", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            }
        },
    )
}
