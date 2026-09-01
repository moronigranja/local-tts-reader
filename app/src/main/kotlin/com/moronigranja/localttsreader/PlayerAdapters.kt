package com.moronigranja.localttsreader

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import com.moronigranja.localttsreader.featureplayer.playback.PlaybackService
import com.moronigranja.localttsreader.featureplayer.playback.PregenManager
import com.moronigranja.localttsreader.featureplayer.playback.PregenWorker
import com.moronigranja.localttsreader.player.PlayerCommands
import com.moronigranja.localttsreader.player.PregenJobState
import com.moronigranja.localttsreader.player.PregenScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A6 composition root: the player command surface behind [PlayerCommands]
 * (intent dispatch to [PlaybackService]) and the pre-generation scheduling
 * contract behind WorkManager ([PregenScheduler]) / the disk tier
 * ([OfflineStorage]). Features depend on the core contracts; only this
 * module knows the implementations.
 */
@Singleton
class PlaybackCommandSender
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : PlayerCommands {
        private fun command(
            action: String,
            extras: Intent.() -> Unit = {},
        ) {
            runCatching {
                context.startForegroundService(
                    Intent(context, PlaybackService::class.java).setAction(action).apply(extras),
                )
            }
        }

        override fun play(bookId: String) =
            command(PlaybackService.ACTION_PLAY) {
                putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
            }

        override fun playAt(
            bookId: String,
            chapterIndex: Int,
            passageIndex: Int,
        ) = command(PlaybackService.ACTION_PLAY_POSITION) {
            putExtra(PlaybackService.EXTRA_BOOK_ID, bookId)
            putExtra(PlaybackService.EXTRA_CHAPTER, chapterIndex)
            putExtra(PlaybackService.EXTRA_PASSAGE, passageIndex)
        }

        override fun changeVoice(voice: String) =
            command(PlaybackService.ACTION_CHANGE_VOICE) {
                putExtra(PlaybackService.EXTRA_VOICE, voice)
            }

        override fun resume() = command(PlaybackService.ACTION_RESUME)

        override fun pause() = command(PlaybackService.ACTION_PAUSE)

        override fun stop() = command(PlaybackService.ACTION_STOP)

        override fun seekForward() = command(PlaybackService.ACTION_SEEK_FORWARD)

        override fun seekBackward() = command(PlaybackService.ACTION_SEEK_BACKWARD)
    }

/** WorkManager surface behind [PregenScheduler] (A6). */
@Singleton
class WorkManagerPregenScheduler
    @Inject
    constructor(
        private val manager: PregenManager,
    ) : PregenScheduler {
        override fun pregenerate(
            bookId: String,
            budgetMinutes: Long?,
        ) = manager.pregenerate(bookId, budgetMinutes)

        override fun cancel(bookId: String) = manager.cancel(bookId)

        override fun observe(bookId: String): Flow<PregenJobState> =
            callbackFlow {
                val live: LiveData<List<WorkInfo>> = manager.workInfo(bookId)
                val observer =
                    Observer<List<WorkInfo>> { infos ->
                        val settled =
                            infos.lastOrNull {
                                it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED ||
                                    it.state == WorkInfo.State.CANCELLED
                            }
                        trySend(
                            PregenJobState(
                                percent = infos.maxOfOrNull { it.progress.getInt(PregenWorker.KEY_PROGRESS_PERCENT, 0) } ?: 0,
                                running = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING },
                                failed = settled?.state == WorkInfo.State.FAILED,
                                error =
                                    if (settled?.state == WorkInfo.State.FAILED) {
                                        settled.outputData.getString(PregenWorker.KEY_ERROR)
                                    } else {
                                        null
                                    },
                            ),
                        )
                    }
                live.observeForever(observer)
                awaitClose { live.removeObserver(observer) }
            }
    }
