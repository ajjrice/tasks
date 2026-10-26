package org.tasks.extensions

import java.util.concurrent.CompletableFuture
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

actual suspend fun pickDirectory(initialDir: String?): String? {
    val result = CompletableFuture<String?>()
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser(initialDir)
        chooser.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            result.complete(chooser.selectedFile?.absolutePath)
        } else {
            result.complete(null)
        }
    }
    return result.get()
}
