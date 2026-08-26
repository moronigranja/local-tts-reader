package com.moronigranja.localttsreader.featureocr

import android.content.Context
import com.moronigranja.localttsreader.ocr.OcrEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Hilt wiring for core-ocr (S1): the tesseract engine behind the OcrEngine
 * seam, and the data path it reads. Consumers (feature-share, the settings
 * OCR section) get the seam; only this module knows tess-two.
 */
@Module
@InstallIn(SingletonComponent::class)
object OcrModule {

    @Provides
    @Singleton
    fun provideTessDataDir(@ApplicationContext context: Context): File =
        TessDataStager.tesseractDataPath(context.filesDir)

    @Provides
    @Singleton
    fun provideOcrEngine(dataDir: File): OcrEngine = TessTwoOcrEngine(dataDir)
}
