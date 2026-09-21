package net.meshsat.android.data

/**
 * Someone an SOS goes to by SMS (MESHSAT-1249). Stored in settings as one "name<TAB>phone" per line;
 * a name can hold neither a tab nor a line break, and [normalisePhone] leaves only digits and a
 * leading plus in the number.
 */
data class EmergencyContact(val name: String, val phone: String) {
    companion object {
        const val MAX = 10

        fun encode(list: List<EmergencyContact>): String =
            list.joinToString("\n") { "${it.name.replace('\t', ' ').replace('\n', ' ')}\t${it.phone}" }

        fun decode(s: String): List<EmergencyContact> =
            s.lineSequence().mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) return@mapNotNull null
                val phone = normalisePhone(line.substring(tab + 1)) ?: return@mapNotNull null
                EmergencyContact(line.substring(0, tab).trim(), phone)
            }.take(MAX).toList()

        /** What came of adding someone: the new list, or why not, in words for the screen. */
        sealed class Added {
            data class Ok(val list: List<EmergencyContact>) : Added()
            data class No(val why: String) : Added()
        }

        /**
         * Add [name] and [rawPhone] to [list], whether they were picked from the phone's contacts
         * or typed. A contacts app hands numbers over as people wrote them ("06 12 34 56 78",
         * "(020) 555-0100"), so the number is normalised here and nowhere else.
         */
        fun adding(list: List<EmergencyContact>, name: String, rawPhone: String): Added {
            if (list.size >= MAX) return Added.No("The list is full: $MAX contacts at most.")
            val phone = normalisePhone(rawPhone)
                ?: return Added.No(if (rawPhone.isBlank()) "That contact has no phone number." else "That is not a phone number.")
            if (list.any { it.phone == phone }) return Added.No("That number is already on the list.")
            val clean = name.replace('\t', ' ').replace('\n', ' ').trim().take(40)
            return Added.Ok(list + EmergencyContact(clean, phone))
        }

        /**
         * A phone number as the phone's SMS service takes it: an optional leading +, then 3 to 15
         * digits (E.164 at most), spaces, dashes, dots and brackets dropped. Null when it is not one.
         */
        fun normalisePhone(raw: String): String? {
            val t = raw.trim()
            val plus = t.startsWith("+")
            val digits = t.removePrefix("+").filterNot { it == ' ' || it == '-' || it == '.' || it == '(' || it == ')' }
            if (digits.length !in 3..15 || !digits.all { it in '0'..'9' }) return null
            return if (plus) "+$digits" else digits
        }
    }
}
