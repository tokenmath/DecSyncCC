/**
 * DecSyncCC - GeneralPrefsActivity.kt
 *
 * Copyright (C) 2018 Aldo Gunsing
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package org.decsync.cc

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import android.view.MenuItem
import androidx.preference.Preference
import com.nononsenseapps.filepicker.FilePickerActivity
import com.nononsenseapps.filepicker.Utils
import org.decsync.cc.contacts.syncAdapterUri
import org.decsync.library.DecsyncException
import org.decsync.library.checkDecsyncInfo
import org.decsync.library.getDefaultDecsyncDir

const val CHOOSE_DECSYNC_DIRECTORY = 0

class GeneralPrefsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.activity_general_prefs)
    }

    override fun onOptionsItemSelected(item: MenuItem) =
            if (item.itemId == android.R.id.home) {
                finish()
                true
            } else {
                false
            }

    @ExperimentalStdlibApi
    class GeneralPrefsFragment : PreferenceFragmentCompat() {

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.general_preferences, rootKey)
        }

        override fun onResume() {
            super.onResume()

            initDecsyncDir()
            initDecsyncDirReset()
        }

        private fun initDecsyncDir() {
            val context = activity!!

            val preference = findPreference<Preference>(PrefUtils.DECSYNC_DIRECTORY)!!
            preference.summary = PrefUtils.getDecsyncDir(context)
            preference.setOnPreferenceClickListener {
                if (!checkCollectionEnabled()) {
                    val intent = Intent(context, FilePickerActivity::class.java)
                    intent.putExtra(FilePickerActivity.EXTRA_ALLOW_CREATE_DIR, true)
                    intent.putExtra(FilePickerActivity.EXTRA_MODE, FilePickerActivity.MODE_DIR)
                    intent.putExtra(FilePickerActivity.EXTRA_START_PATH, PrefUtils.getDecsyncDir(context))
                    startActivityForResult(intent, CHOOSE_DECSYNC_DIRECTORY)
                }
                true
            }
        }

        private fun initDecsyncDirReset() {
            val context = activity!!

            val preference = findPreference<Preference>(PrefUtils.DECSYNC_DIRECTORY_RESET)!!
            preference.setOnPreferenceClickListener {
                if (!checkCollectionEnabled()) {
                    AlertDialog.Builder(context)
                            .setTitle("Reset DecSync directory")
                            .setMessage("Do you want to reset the DecSync directory to the default location '${getDefaultDecsyncDir()}'?")
                            .setNegativeButton("No") { _, _ -> }
                            .setPositiveButton("Yes") { _, _ ->
                                setDecsyncDir(getDefaultDecsyncDir())
                            }
                            .show()
                }
                true
            }
        }

        private fun checkCollectionEnabled(): Boolean {
            val context = activity!!

            val addressBookAccounts = AccountManager.get(context).getAccountsByType(getString(R.string.account_type_contacts))
            val anyAddressBookEnabled = addressBookAccounts.isNotEmpty()

            var anyCalendarEnabled = false
            val calendarsAccount = Account(getString(R.string.account_name_calendars), getString(R.string.account_type_calendars))
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
                context.contentResolver.acquireContentProviderClient(CalendarContract.AUTHORITY)?.let { provider ->
                    anyCalendarEnabled = try {
                        provider.query(syncAdapterUri(calendarsAccount, CalendarContract.Calendars.CONTENT_URI), emptyArray(),
                                null, null, null)!!.use { cursor ->
                            cursor.moveToFirst()
                        }
                    } finally {
                        if (Build.VERSION.SDK_INT >= 24)
                            provider.close()
                        else
                            @Suppress("DEPRECATION")
                            provider.release()
                    }
                }
            }

            return if (anyAddressBookEnabled || anyCalendarEnabled) {
                AlertDialog.Builder(context)
                        .setTitle("Enabled collections")
                        .setMessage("There are still some collections enabled. Disable all collections before changing the DecSync directory.")
                        .setNeutralButton("OK") { _, _ -> }
                        .show()
                true
            } else {
                false
            }
        }

        private fun setDecsyncDir(dir: String) {
            val context = activity!!
            try {
                checkDecsyncInfo(dir)
                PrefUtils.putDecsyncDir(context, dir)
                findPreference<Preference>(PrefUtils.DECSYNC_DIRECTORY)!!.summary = dir
            } catch (e: DecsyncException) {
                AlertDialog.Builder(context)
                        .setTitle("DecSync")
                        .setMessage(e.message)
                        .setPositiveButton("OK") { _, _ -> }
                        .show()
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
            super.onActivityResult(requestCode, resultCode, data)

            if (requestCode == CHOOSE_DECSYNC_DIRECTORY) {
                val context = activity!!
                val uri = data?.data
                if (resultCode == Activity.RESULT_OK && uri != null) {
                    val oldDir = PrefUtils.getDecsyncDir(context)
                    val newDir = Utils.getFileForUri(uri).path
                    if (oldDir != newDir) {
                        setDecsyncDir(newDir)
                    }
                }
            }
        }
    }
}
