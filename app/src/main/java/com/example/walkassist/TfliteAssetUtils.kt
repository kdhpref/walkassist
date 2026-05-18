package com.example.walkassist

import android.content.Context
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

object TfliteAssetUtils {
    fun loadMappedAsset(context: Context, assetName: String): MappedByteBuffer {
        context.assets.openFd(assetName).use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).use { input ->
                return input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    descriptor.startOffset,
                    descriptor.declaredLength,
                )
            }
        }
    }

    fun loadLabels(context: Context, assetName: String): List<String> {
        return context.assets.open(assetName).bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter(String::isNotEmpty)
                .toList()
        }
    }
}
