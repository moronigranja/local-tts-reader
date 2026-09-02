package com.moronigranja.localttsreader.featurelibrary

/**
 * The import flow's UI state, driven by [LibraryViewModel.import].
 * Every batch ends in [Done] — never left stuck on a failure.
 */
sealed interface ImportUiState {

    /** No import in flight. */
    data object Idle : ImportUiState

    /** Folder scan in flight (F3); [description] explains the phase, no file count yet. */
    data class Scanning(val description: String) : ImportUiState

    /** Import in progress; [done] of [total] files processed ([currentFileName] = the one just done). */
    data class Importing(val done: Int, val total: Int, val currentFileName: String) : ImportUiState

    /** Batch finished; [summary] aggregates every outcome. */
    data class Done(val summary: Summary) : ImportUiState

    /** Batch result: counts plus per-file failures (fileName, user-facing message). */
    data class Summary(
        val added: Int,
        val unchanged: Int,
        val failed: List<Pair<String, String>>,
        /** True when a folder scan hit [FolderScanPolicy.MAX_FILES] and more files remain unseen. */
        val truncated: Boolean = false,
    )
}
