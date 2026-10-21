package io.github.wzehirx.bpdf

import android.content.Context
import io.github.wzehirx.bpdf.core.files.FileRepository

/** Manual dependency container; ViewModels obtain dependencies from it via `viewModelFactory`. */
class AppContainer(context: Context) {
    val fileRepository: FileRepository = FileRepository(context)
}
