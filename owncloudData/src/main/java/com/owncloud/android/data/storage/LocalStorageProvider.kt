/**
 * ownCloud Android client application
 *
 * @author David A. Velasco
 * @author David González Verdugo
 * @author Christian Schabesberger
 * @author Shashvat Kedia
 * @author Juan Carlos Garrote Gascón
 * <p>
 * Copyright (C) 2021 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.owncloud.android.data.storage

import android.accounts.Account
import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis

sealed class LocalStorageProvider(val rootFolderName: String) {

    abstract fun getPrimaryStorageDirectory(): File

    class ScopedStorageProvider(
        rootFolderName: String,
        private val context: Context
    ) : LocalStorageProvider(rootFolderName) {

        override fun getPrimaryStorageDirectory(): File = context.filesDir

        fun moveLegacyToScopedStorage() {
            val timeInMillis = measureTimeMillis {
                moveFileOrFolderToScopedStorage(retrieveRootLegacyStorage())
            }
            Timber.d("MIGRATED FILES IN ${TimeUnit.SECONDS.convert(timeInMillis, TimeUnit.MILLISECONDS)} seconds")

        }

        fun copyLegacyToScopedStorage() {
            val timeInMillis = measureTimeMillis {
                copyFileOrFolderToScopedStorage(retrieveRootLegacyStorage())
            }
            Timber.d("Migrated files in ${TimeUnit.SECONDS.convert(timeInMillis, TimeUnit.MILLISECONDS)} seconds")

        }

        private fun retrieveRootLegacyStorage(): File {
            val legacyStorageProvider = LegacyStorageProvider(rootFolderName)
            val rootLegacyStorage = File(legacyStorageProvider.getRootFolderPath())

            val legacyStorageUsedBytes = sizeOfDirectory(rootLegacyStorage)
            Timber.d(
                "Root ${rootLegacyStorage.absolutePath} has ${rootLegacyStorage.listFiles()?.size} files and its size is $legacyStorageUsedBytes Bytes"
            )

            return rootLegacyStorage
        }

        private fun copyFileOrFolderToScopedStorage(file: File) {
            Timber.d("Let's copy ${file.absolutePath} to scoped storage")
            file.copyRecursively(File(getRootFolderPath()), overwrite = true)
        }

        private fun moveFileOrFolderToScopedStorage(file: File) {
            copyFileOrFolderToScopedStorage(file)
            Timber.d("Let's delete legacy storage ${file.absolutePath}")
            file.deleteRecursively()
        }
    }

    fun sizeOfDirectory(dir: File): Long {
        if (dir.exists()) {
            var result: Long = 0
            val fileList = dir.listFiles() ?: arrayOf()
            fileList.forEach { file ->
                // Recursive call if it's a directory
                result += if (file.isDirectory) {
                    sizeOfDirectory(file)
                } else {
                    // Sum the file size in bytes
                    file.length()
                }
            }
            return result // return the file size
        }
        return 0
    }

    /**
     * Return the root path of primary shared/external storage directory for this application.
     * For example: /storage/emulated/0/owncloud
     */
    fun getRootFolderPath(): String = getPrimaryStorageDirectory().absolutePath + File.separator + rootFolderName

    /**
     * Get local storage path for accountName.
     */
    fun getAccountDirectoryPath(
        accountName: String?
    ): String = getRootFolderPath() + File.separator + getEncodedAccountName(accountName)

    /**
     * Get local path where OCFile file is to be stored after upload. That is,
     * corresponding local path (in local owncloud storage) to remote uploaded
     * file.
     */
    fun getDefaultSavePathFor(
        accountName: String?,
        remotePath: String
    ): String = getAccountDirectoryPath(accountName) + remotePath

    /**
     * Get absolute path to tmp folder inside datafolder in sd-card for given accountName.
     */
    fun getTemporalPath(
        accountName: String?
    ): String = getRootFolderPath() + "/tmp/" + getEncodedAccountName(accountName)

    fun getLogsPath(): String = getRootFolderPath() + LOGS_FOLDER_NAME

    /**
     * Optimistic number of bytes available on sd-card.
     *
     * @return Optimistic number of available bytes (can be less)
     */
    @SuppressLint("UsableSpace")
    fun getUsableSpace(): Long = getPrimaryStorageDirectory().usableSpace

    /**
     * Checks if there is user data which does not have a corresponding account in the Account manager.
     */
    private fun getDanglingAccountDirs(remainingAccounts: Array<Account>): List<File> {
        val rootFolder = File(getRootFolderPath())
        val danglingDirs = mutableListOf<File>()
        if (rootFolder.listFiles() != null) {
            for (dir in rootFolder.listFiles()) {
                var dirIsOk = false
                if (dir.name.equals("tmp")) {
                    dirIsOk = true
                } else {
                    for (a in remainingAccounts) {
                        if (dir.name.equals(getEncodedAccountName(a.name))) {
                            dirIsOk = true
                        }
                    }
                }
                if (!dirIsOk) {
                    danglingDirs.add(dir)
                }
            }
        }
        return danglingDirs
    }

    open fun deleteUnusedUserDirs(remainingAccounts: Array<Account>) {
        val danglingDirs = getDanglingAccountDirs(remainingAccounts)
        for (dd in danglingDirs) {
            dd.deleteRecursively()
        }
    }

    /**
     * URL encoding is an 'easy fix' to overcome that NTFS and FAT32 don't allow ":" in file names,
     * that can be in the accountName since 0.1.190B
     */
    private fun getEncodedAccountName(accountName: String?): String = Uri.encode(accountName, "@")

    companion object {
        private const val LOGS_FOLDER_NAME = "/logs/"
    }
}
