package com.moronigranja.localttsreader.featurelibrary

import javax.inject.Qualifier

/**
 * Qualifier for the app-wide IO [kotlinx.coroutines.CoroutineDispatcher]
 * (production: [kotlinx.coroutines.Dispatchers.IO]; tests: a virtual dispatcher).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher
