package org.sonorus.player

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What this phone decodes, in the words the server's `planPlayback` reads.
 *
 * ExoPlayer plays Matroska and picks among audio tracks itself, so `hevcMkv`
 * and `tracks` are always true; the codecs depend on the device. HEVC counts
 * only with Main10, because a good part of the library is 10-bit and a phone
 * that stalls on it is worse off than one that gets an H.264 copy.
 * MPEG-2 is deliberately not reported: DVDs are interlaced, and only the
 * server's encode deinterlaces them.
 */
object VideoCaps {

    val json: JsonObject by lazy { detect() }

    private fun detect(): JsonObject {
        val decoders = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }
            .getOrDefault(emptyArray())
            .filter { !it.isEncoder }

        fun has(mime: String) = decoders.any { info -> info.supportedTypes.any { it.equals(mime, true) } }

        val hevcMain10 = decoders.any { info ->
            info.supportedTypes.any { it.equals("video/hevc", true) } && runCatching {
                info.getCapabilitiesForType("video/hevc").profileLevels
                    .any { it.profile == CodecProfileLevel.HEVCProfileMain10 }
            }.getOrDefault(false)
        }
        val audio = listOfNotNull(
            "ac3".takeIf { has("audio/ac3") },
            "eac3".takeIf { has("audio/eac3") },
            "dts".takeIf { has("audio/vnd.dts") },
            "truehd".takeIf { has("audio/true-hd") },
        )
        return buildJsonObject {
            put("hevc", hevcMain10)
            put("av1", has("video/av01"))
            put("vp9", has("video/x-vnd.on2.vp9"))
            put("hevcMkv", true)
            put("tracks", true)
            put("audio", JsonArray(audio.map { JsonPrimitive(it) }))
        }
    }
}
