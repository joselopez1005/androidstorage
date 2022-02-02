package com.jlopez.androidstorage

import android.graphics.Bitmap
import android.net.Uri

data class SharedStoragePhoto(
    val id: Long,           // Given by media store
    val name: String,
    val width: Int,
    val height: Int,
    val contentUri: Uri     // URI on where to actually find the file
)
