package com.moronigranja.localttsreader

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** App-level Hilt container; the DI root for feature modules (C5/C6). */
@HiltAndroidApp
class LocalTtsReaderApp : Application()
