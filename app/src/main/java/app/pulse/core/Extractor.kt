package app.pulse.core

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Thin wrapper over NewPipeExtractor's YouTube service — search + trending + stream resolution. Call off the main thread. */
object Extractor {

    private val yt = ServiceList.YouTube

    fun searchMusic(query: String): List<StreamItem> {
        val handler = yt.searchQHFactory.fromQuery(query, listOf("music_songs"), "")
        val info = SearchInfo.getInfo(yt, handler)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toItem() }
    }

    fun trending(): List<StreamItem> {
        val kl = yt.kioskList
        val id = kl.defaultKioskId
        val handler = kl.getListLinkHandlerFactoryByType(id).fromId(id)
        val info = KioskInfo.getInfo(yt, handler.url)
        return info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toItem() }
    }

    fun streamInfo(url: String): StreamData {
        val info = StreamInfo.getInfo(yt, url)
        val audioUrl = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.averageBitrate }
            ?.content
        val videoUrl = info.videoStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
            .maxByOrNull { it.resolution.heightValue() }
            ?.content
        return StreamData(
            url = url,
            title = info.name,
            uploader = info.uploaderName ?: "",
            durationMs = info.duration * 1000,
            thumbnailUrl = info.thumbnails.bestUrl(),
            audioUrl = audioUrl,
            videoUrl = videoUrl,
        )
    }

    private fun StreamInfoItem.toItem() = StreamItem(
        url = url,
        title = name,
        uploader = uploaderName ?: "",
        durationSec = duration,
        thumbnailUrl = thumbnails.bestUrl(),
    )

    private fun List<Image>.bestUrl(): String? = maxByOrNull { it.height }?.url

    private fun String.heightValue(): Int = takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}
