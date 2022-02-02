package com.jlopez.androidstorage

import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.ContentObservable
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import com.plcoding.androidstorage.R
import com.plcoding.androidstorage.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.lang.Exception
import java.util.*
import java.util.jar.Manifest

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var internalStoragePhotoAdapter: InternalStoragePhotoAdapter
    private lateinit var externalStoragePhotoAdapter: SharedPhotoAdapter

    private var readPermissionGranted = false
    private var writePermissionGranted = false

    //Array of strings because want to ask for permissions for every permission not granted
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private lateinit var contentObserver: ContentObserver // Can be thought of as an observer of live data but for storage



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        internalStoragePhotoAdapter = InternalStoragePhotoAdapter {
            lifecycleScope.launch{
                val isDeletionSuccessful = deletePhotoFromInternalStorage(it.name)
                if(isDeletionSuccessful) {
                    loadPhotosFromInternalStorageIntoRecyclerView()
                    Toast.makeText(this@MainActivity, "Photo deleted successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Photo was not able to be removed", Toast.LENGTH_SHORT).show()
                }
            }
        }

        externalStoragePhotoAdapter = SharedPhotoAdapter {

        }

        setupExternalStorageRecyclerView()
        initContentObserver()


        permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            readPermissionGranted = permissions[android.Manifest.permission.READ_EXTERNAL_STORAGE] ?: readPermissionGranted
            writePermissionGranted = permissions[android.Manifest.permission.WRITE_EXTERNAL_STORAGE] ?: writePermissionGranted

            if(readPermissionGranted) {
                loadPhotosFromExternalStorageIntoRecyclerView()
            } else {
                Toast.makeText(this, "Can't read files without permission", Toast.LENGTH_SHORT).show()
            }
        }

        updateOrRequestPermissions()

        val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) {
            lifecycleScope.launch{
                val isPrivate = binding.switchPrivate.isChecked
                val isSavedSuccessfully = when {
                    isPrivate -> savePhotoToInternalStorage(UUID.randomUUID().toString(), it)
                    writePermissionGranted -> savePhotoToExternalStorage(UUID.randomUUID().toString(), it)
                    else -> false
                }
                if(isPrivate) {
                    loadPhotosFromInternalStorageIntoRecyclerView()
                }
                if(isSavedSuccessfully) {
                    Toast.makeText(this@MainActivity, "Photo saved successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "Failed to save photo", Toast.LENGTH_SHORT).show()
                }
            }
        }


        binding.btnTakePhoto.setOnClickListener{
            takePhoto.launch()
        }

        setupInternalStorageRecyclerView()
        loadPhotosFromInternalStorageIntoRecyclerView()
        loadPhotosFromExternalStorageIntoRecyclerView()
    }

    private fun initContentObserver() {
        contentObserver = object: ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                if(readPermissionGranted) {
                    loadPhotosFromExternalStorageIntoRecyclerView()
                }
            }
        }
        // We can actually register it to contentResolver to find changes
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // If uri changes
            contentObserver
        )
    }

    /*
    This function is quite interesting. The first thing to do when loading from external storage
    is to first find the uri where external storage is at. We can think of this storage as a database. The
    next step is to get the projection which is basically what columns we want to get from the database.
    After that, is a matter of getting it and processing it using cursor. Cursor will loop through
    the list of columns that we got and we will create our item objects.
     */
    private suspend fun loadPhotosFromExternalStorage(): List<SharedStoragePhoto> {

        return withContext(Dispatchers.IO) {
            val collection = sdk29AndUp {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)   // Since reading we can use any volume
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.WIDTH,
            )

            val photos = mutableListOf<SharedStoragePhoto> ()

            contentResolver.query(
                collection,         // Where to find files
                projection,         // What columns to get (what comes after select)
                null,       // What items to get (What comes after where),
                null,
                "${MediaStore.Images.Media.DISPLAY_NAME} ASC" // How to sort hte resuts
            )?.use { cursor ->  // Cursor used to iterate
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val displayName = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                while(cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(displayName)
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    photos.add(SharedStoragePhoto(id, name, width, height, contentUri))
                }
                photos.toList()
            } ?: listOf()

        }
    }

    private fun updateOrRequestPermissions() {
        val hasReadPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        val hasWritePermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

        // Write external permission not needed after sdk level 29.
        val minSdk29 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        readPermissionGranted = hasReadPermission
        writePermissionGranted = hasWritePermission || minSdk29

        val permissionsToRequest = mutableListOf<String>()
        if(!writePermissionGranted) {
            permissionsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if(!readPermissionGranted) {
            permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Note: In production app, if user denies or permanently denies permissions. Send
        // dialogue saying app cannot work without these permissions.
        if(permissionsToRequest.isNotEmpty()) {
            permissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private suspend fun savePhotoToExternalStorage(displayName: String, bmp: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
            // Media store - Database for all types of media files and corresponding metadata
            // Get collection for images and save into media store
            // Need to get URI which depend on the version. URI will be like the address of the file
            val imageCollection = sdk29AndUp {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            // Meta data for the file
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$displayName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.HEIGHT, bmp.height)
                put(MediaStore.Images.Media.WIDTH, bmp.width)
            }

            try {
                /*
                To actually store into media store first we need a content resolver, which will place
                the metadata along with the uri provided depending on sdk version. Once that is done,
                content resovler will give us another uri leading to the actually media store database.
                Here we can open up an output stream where we store the file just like we did with
                local storage.
                 */
                contentResolver.insert(imageCollection, contentValues)?.also { uri ->
                    contentResolver.openOutputStream(uri).use { outputStream ->
                        if(!bmp.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                            throw IOException("Couldn't save bitmap")
                        }
                    }
                } ?: throw IOException("Coudln't create MediaStore Entry")
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }

    private fun setupInternalStorageRecyclerView() = binding.rvPrivatePhotos.apply {
        adapter = internalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager(3, RecyclerView.VERTICAL)
    }

    private fun setupExternalStorageRecyclerView() = binding.rvPrivatePhotos.apply {
        adapter = externalStoragePhotoAdapter
        layoutManager = StaggeredGridLayoutManager(3, RecyclerView.VERTICAL)
    }

    private fun loadPhotosFromInternalStorageIntoRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotoFromInternalStorage()
            internalStoragePhotoAdapter.submitList(photos)
        }
    }

    private fun loadPhotosFromExternalStorageIntoRecyclerView() {
        lifecycleScope.launch {
            val photos = loadPhotosFromExternalStorage()
            externalStoragePhotoAdapter.submitList(photos)
        }
    }

    private suspend fun deletePhotoFromInternalStorage(fileName: String): Boolean {
        return withContext(Dispatchers.IO){
            try {
                deleteFile(fileName)
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    /*
     * Will load images from internal storage. Will return a string so do not want to block
     * the main thread for the case that there are a lot of files
     */
    private suspend fun loadPhotoFromInternalStorage(): List<InternalStoragePhoto> {
        return withContext(Dispatchers.IO) {
            val files = filesDir.listFiles()            //filesDir contains the root dir for our internal storage
            files?.filter { it.canRead() && it.isFile && it.name.endsWith(".jpg") }?.map {
                val bytes = it.readBytes()
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                InternalStoragePhoto(it.name, bmp)
            } ?: listOf()
        }
    }
    /*
     * Bitmap can be thought as a bunch of bytes together.
     * When saving this bytes, we need an output stream
     * Input Stream: Bytes from a file and place them into bitmap or anything else
     * Output Stream: Bytes from bitmap and place them into a file
     * Android needs to know how we interpret this bytes
     */
    private suspend fun savePhotoToInternalStorage(filename: String, bmp: Bitmap): Boolean {
        return withContext(Dispatchers.IO) {
             try {
                // Output stream and mode private means that it will only be available to us.
                // The .use function will close the output stream after using it
                openFileOutput("$filename.jpg", MODE_PRIVATE).use { stream ->
                    /*
                    What this basically does is will say that we are going to put the bytes from
                    bitmap into our stream and from that stream into our filename.jog
                    The function compress will actually take care of that process
                     */
                    if(!bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                        throw IOException("Couldn't save bitmap")
                    }
                }
                true
            } catch (e: IOException) {
                e.printStackTrace()
                false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(contentObserver)
    }
}