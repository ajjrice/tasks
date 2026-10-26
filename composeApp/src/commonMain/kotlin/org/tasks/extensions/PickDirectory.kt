package org.tasks.extensions

/**
 * Opens a native directory chooser and returns the selected directory,
 * or null when the user cancels or the platform does not support picking.
 */
expect suspend fun pickDirectory(initialDir: String?): String?
