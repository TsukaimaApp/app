package app.tsukaima.ui.chat

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** A file in the app's cache (a shared attachment), picked up like a document from the system picker. */
class FilePickedFile(private val file: File, private val mime: String?) : PickedFile {
    override fun describe(): FileInfo = FileInfo(file.name, mime, file.length())

    override fun open(): InputStream = FileInputStream(file)
}
