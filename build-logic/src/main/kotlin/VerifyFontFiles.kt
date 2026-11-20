import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Fails when a file under composeResources/font is not a real TrueType/OpenType font. Saved GitHub HTML pages named
 * *.ttf once shipped and the app silently fell back to the system font. Compose resources cannot be read from host
 * tests, so this checks the files' signature, extension and sfnt table directory directly.
 */
abstract class VerifyFontFiles : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fonts: ConfigurableFileCollection

    /** Written on success so an unchanged font folder leaves the task UP-TO-DATE. */
    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val nonFonts = fonts.files.filter { !isRealFont(it) }
        if (nonFonts.isNotEmpty()) {
            val details = nonFonts.joinToString("; ") { "${it.name} (starts with ${describeHeader(it)})" }
            val noun = if (nonFonts.size == 1) "file is" else "files are"
            throw GradleException(
                "Font $noun not a valid TrueType/OpenType font: $details. " +
                    "Replace with a real .ttf (TrueType 1.0 or 'true') or .otf ('OTTO') file.",
            )
        }
        marker.get().asFile.apply {
            parentFile.mkdirs()
            writeText("ok")
        }
    }

    private fun isRealFont(file: File): Boolean {
        val bytes = file.readBytes()
        val signature = signatureOf(bytes)
        val extensionMatches = FONT_SIGNATURES[signature]?.equals(file.extension, ignoreCase = true) == true
        val numTables = if (bytes.size >= HEADER_SIZE_BYTES) readUInt16(bytes, NUM_TABLES_OFFSET) else 0
        return extensionMatches && numTables > 0 && bytes.size >= HEADER_SIZE_BYTES + TABLE_RECORD_SIZE_BYTES * numTables
    }

    private fun signatureOf(bytes: ByteArray) = bytes.take(SIGNATURE_SIZE_BYTES).joinToString("") { "%02x".format(it) }

    private fun describeHeader(file: File): String {
        val head = file.inputStream().use { it.readNBytes(SIGNATURE_SIZE_BYTES) }
        val ascii = String(head.map { if (it in PRINTABLE_ASCII) it else '.'.code.toByte() }.toByteArray(), Charsets.US_ASCII)
        return "${signatureOf(head)} \"$ascii\""
    }

    private fun readUInt16(
        bytes: ByteArray,
        offset: Int,
    ) = ((bytes[offset].toInt() and BYTE_MASK) shl BITS_PER_BYTE) or (bytes[offset + 1].toInt() and BYTE_MASK)

    private companion object {
        const val SIGNATURE_SIZE_BYTES = 4
        const val HEADER_SIZE_BYTES = 12
        const val TABLE_RECORD_SIZE_BYTES = 16
        const val NUM_TABLES_OFFSET = 4
        const val BYTE_MASK = 0xff
        const val BITS_PER_BYTE = 8
        val PRINTABLE_ASCII = 0x20..0x7e

        // Signature (hex) to the extension that Compose resources expect for it: TrueType 1.0, 'true', and 'OTTO'.
        val FONT_SIGNATURES = mapOf("00010000" to "ttf", "74727565" to "ttf", "4f54544f" to "otf")
    }
}
