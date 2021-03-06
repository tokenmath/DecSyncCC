/**
 * DecSyncCC - DecsyncUtils.kt
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

package org.decsync.cc.contacts

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.util.Log
import at.bitfire.vcard4android.*
import kotlinx.serialization.json.*
import org.decsync.cc.*
import org.decsync.library.Decsync
import java.io.StringReader

const val TAG = "DecSync Contacts"
const val KEY_NUM_PROCESSED_ENTRIES = "num-processed-entries"

@ExperimentalStdlibApi
object ContactDecsyncUtils {
    fun infoListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        Log.d(TAG, "Execute info entry $entry")
        val info = entry.key.content
        when (info) {
            "deleted" -> {
                val deleted = entry.value.boolean
                if (!deleted) {
                    return
                }
                Log.d(TAG, "Delete address book ${extra.info.name}")

                val account = extra.info.getAccount(extra.context)

                // Delete the contacts
                extra.provider.delete(syncAdapterUri(account, RawContacts.CONTENT_URI),
                        "${LocalContact.COLUMN_LOCAL_BOOKID}=?", arrayOf(extra.info.id))

                // Delete the account if it has no contacts
                extra.provider.query(syncAdapterUri(account, RawContacts.CONTENT_URI), emptyArray(),
                        null, null, null)!!.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        if (Build.VERSION.SDK_INT >= 22) {
                            AccountManager.get(extra.context).removeAccountExplicitly(account)
                        } else {
                            @Suppress("deprecation")
                            AccountManager.get(extra.context).removeAccount(account, null, null)
                        }
                    }
                }
            }
            "name" -> {
                val name = entry.value.content
                Log.d(TAG, "Rename address book ${extra.info.name} to $name")

                // It is possible to temporarily have 2 accounts with the same name, since the names may be swapped
                val oldAccount = extra.info.getAccount(extra.context)
                val newAccount = Account(name, oldAccount.type)
                val accountManager = AccountManager.get(extra.context)

                // Create the new account if needed
                accountManager.addAccountExplicitly(newAccount, null, null)
                accountManager.setUserData(newAccount, "id", extra.info.id) // Separate, since the account may exist
                accountManager.setUserData(newAccount, KEY_NUM_PROCESSED_ENTRIES, null)
                ContentResolver.setSyncAutomatically(newAccount, ContactsContract.AUTHORITY, true)
                ContentResolver.addPeriodicSync(newAccount, ContactsContract.AUTHORITY, Bundle(), 60 * 60)

                // Move the contacts to the new account
                val values = ContentValues()
                values.put(RawContacts.ACCOUNT_NAME, name)
                extra.provider.update(syncAdapterUri(oldAccount, RawContacts.CONTENT_URI), values,
                        "${LocalContact.COLUMN_LOCAL_BOOKID}=?", arrayOf(extra.info.id))

                // Delete the old account if it has no contacts
                extra.provider.query(syncAdapterUri(oldAccount, RawContacts.CONTENT_URI), emptyArray(),
                        null, null, null)!!.use { cursor ->
                    if (!cursor.moveToFirst()) {
                        if (Build.VERSION.SDK_INT >= 22) {
                            AccountManager.get(extra.context).removeAccountExplicitly(oldAccount)
                        } else {
                            @Suppress("deprecation")
                            AccountManager.get(extra.context).removeAccount(oldAccount, null, null)
                        }
                    }
                }
            }
            else -> {
                Log.w(TAG, "Unknown info key $info")
            }
        }
    }

    fun resourcesListener(path: List<String>, entry: Decsync.Entry, extra: Extra) {
        if (path.size != 1) {
            Log.w(TAG, "Invalid path $path")
            return
        }
        val uid = path[0]
        val vcard = entry.value.contentOrNull

        val account = extra.info.getAccount(extra.context)
        val bookId = extra.info.id
        val id = extra.provider.query(syncAdapterUri(account, RawContacts.CONTENT_URI), arrayOf(RawContacts._ID),
                "${LocalContact.COLUMN_LOCAL_UID}=?", arrayOf(uid), null)!!.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }

        val addressBook = AndroidAddressBook(account, extra.provider, LocalContact.ContactFactory, LocalContact.GroupFactory)
        when (vcard) {
            null -> {
                if (id == null) {
                    Log.w(TAG, "Unknown contact $uid cannot be deleted")
                } else {
                    Log.d(TAG, "Delete contact $uid")
                    val values = ContentValues()
                    values.put(RawContacts._ID, id)
                    LocalContact(addressBook, values).delete()
                    addToNumProcessedEntries(extra, -1)
                }
            }
            else -> {
                val contacts = Contact.fromReader(StringReader(vcard), null)
                if (contacts.isEmpty()) {
                    Log.w(TAG, "No contacts found in vCard $vcard")
                    return
                }
                if (contacts.size > 1) {
                    Log.w(TAG, "Multiple contacts found in vCard $vcard")
                    return
                }
                val contact = contacts[0]
                if (id == null) {
                    Log.d(TAG, "Add contact $uid")
                    LocalContact(addressBook, contact, uid, bookId).add()
                    addToNumProcessedEntries(extra, 1)
                } else {
                    Log.d(TAG, "Update contact $uid")
                    val values = ContentValues()
                    values.put(RawContacts._ID, id)
                    values.put(LocalContact.COLUMN_LOCAL_UID, uid)
                    values.put(LocalContact.COLUMN_LOCAL_BOOKID, bookId)
                    LocalContact(addressBook, values).update(contact)
                }
            }
        }
    }
}
