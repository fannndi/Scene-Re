package com.omarea.vtools

class SceneJNI {
    external fun getKernelPropLong(path: String): Long

    /**
     * Walk [path] and read every file up to [budgetMb] into the page cache.
     * Returns the number of bytes touched, or -1 when nothing was readable.
     */
    external fun preloadPath(path: String, budgetMb: Long): Long

    companion object {
        // Used to load the 'native-lib' library on application startup.
        init {
            System.loadLibrary("native-lib")
        }
    }
}