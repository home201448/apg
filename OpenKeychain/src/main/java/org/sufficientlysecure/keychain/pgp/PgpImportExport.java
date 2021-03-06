/*
 * Copyright (C) 2012-2014 Dominik Schürmann <dominik@dominikschuermann.de>
 * Copyright (C) 2010-2014 Thialfihar <thi@thialfihar.org>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.thialfihar.android.apg.pgp;

import android.content.Context;
import android.os.Bundle;
import android.os.Environment;

import org.spongycastle.bcpg.ArmoredOutputStream;
import org.spongycastle.openpgp.PGPException;
import org.spongycastle.openpgp.PGPKeyRing;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyRing;
import org.spongycastle.openpgp.PGPSecretKey;
import org.spongycastle.openpgp.PGPSecretKeyRing;
import org.spongycastle.openpgp.operator.jcajce.JcaKeyFingerprintCalculator;

import org.thialfihar.android.apg.Constants;
import org.thialfihar.android.apg.R;
import org.thialfihar.android.apg.pgp.exception.PgpGeneralException;
import org.thialfihar.android.apg.provider.ProviderHelper;
import org.thialfihar.android.apg.service.ApgIntentService;
import org.thialfihar.android.apg.keyimport.ImportKeysListEntry;
import org.thialfihar.android.apg.keyimport.Keyserver;
import org.thialfihar.android.apg.util.IterableIterator;
import org.thialfihar.android.apg.keyimport.Keyserver.AddKeyException;
import org.thialfihar.android.apg.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class PgpImportExport {

    // TODO: is this really used?
    public interface ApgServiceListener {
        boolean hasServiceStopped();
    }

    private Context mContext;
    private Progressable mProgressable;

    private ApgServiceListener mApgServiceListener;

    private ProviderHelper mProviderHelper;

    public static final int RETURN_OK = 0;
    public static final int RETURN_ERROR = -1;
    public static final int RETURN_BAD = -2;
    public static final int RETURN_UPDATED = 1;

    public PgpImportExport(Context context, Progressable progressable) {
        super();
        this.mContext = context;
        this.mProgressable = progressable;
        this.mProviderHelper = new ProviderHelper(context);
    }

    public PgpImportExport(Context context,
                           Progressable progressable, ApgServiceListener apgListener) {
        super();
        this.mContext = context;
        this.mProgressable = progressable;
        this.mProviderHelper = new ProviderHelper(context);
        this.mApgServiceListener = apgListener;
    }

    public void updateProgress(int message, int current, int total) {
        if (mProgressable != null) {
            mProgressable.setProgress(message, current, total);
        }
    }

    public void updateProgress(String message, int current, int total) {
        if (mProgressable != null) {
            mProgressable.setProgress(message, current, total);
        }
    }

    public void updateProgress(int current, int total) {
        if (mProgressable != null) {
            mProgressable.setProgress(current, total);
        }
    }

    public boolean uploadKeyRingToServer(Keyserver server, PGPPublicKeyRing keyRing) {
        try {
            server.add(new KeyRing(keyRing).getArmoredEncoded(mContext));
            return true;
        } catch (IOException e) {
            return false;
        } catch (AddKeyException e) {
            // TODO: tell the user?
            return false;
        }
    }

    /**
     * Imports keys from given data. If keyIds is given only those are imported
     */
    public Bundle importKeyRings(List<ImportKeysListEntry> entries)
            throws PgpGeneralException, PGPException, IOException {
        Bundle returnData = new Bundle();

        updateProgress(R.string.progress_importing, 0, 100);

        int newKeys = 0;
        int oldKeys = 0;
        int badKeys = 0;

        int position = 0;
        try {
            for (ImportKeysListEntry entry : entries) {
                KeyRing keyRing = KeyRing.decode(entry.getBytes());

                if (keyRing != null) {
                    int status;
                    if (keyRing.isPublic()) {
                        status = storeKeyRingInCache(keyRing.getPublicKeyRing());
                    } else {
                        status = storeKeyRingInCache(keyRing.getSecretKeyRing());
                    }

                    if (status == RETURN_ERROR) {
                        throw new PgpGeneralException(
                                mContext.getString(R.string.error_saving_keys));
                    }

                    // update the counts to display to the user at the end
                    if (status == RETURN_UPDATED) {
                        ++oldKeys;
                    } else if (status == RETURN_OK) {
                        ++newKeys;
                    } else if (status == RETURN_BAD) {
                        ++badKeys;
                    }
                } else {
                    Log.e(Constants.TAG, "Object not recognized as PGPKeyRing!", new Exception());
                }

                position++;
                updateProgress(position / entries.size() * 100, 100);
            }
        } catch (Exception e) {
            Log.e(Constants.TAG, "Exception on parsing key file!", e);
        }

        returnData.putInt(ApgIntentService.RESULT_IMPORT_ADDED, newKeys);
        returnData.putInt(ApgIntentService.RESULT_IMPORT_UPDATED, oldKeys);
        returnData.putInt(ApgIntentService.RESULT_IMPORT_BAD, badKeys);

        return returnData;
    }

    public Bundle exportKeyRings(ArrayList<Long> publicKeyRingMasterIds,
                                 ArrayList<Long> secretKeyRingMasterIds,
                                 OutputStream outStream) throws PgpGeneralException,
            PGPException, IOException {
        Bundle returnData = new Bundle();

        int masterKeyIdsSize = publicKeyRingMasterIds.size() + secretKeyRingMasterIds.size();
        int progress = 0;

        updateProgress(
                mContext.getResources().getQuantityString(R.plurals.progress_exporting_key,
                        masterKeyIdsSize), 0, 100);

        if (!Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            throw new PgpGeneralException(
                    mContext.getString(R.string.error_external_storage_not_ready));
        }
        // For each public masterKey id
        for (long pubKeyMasterId : publicKeyRingMasterIds) {
            progress++;
            // Create an output stream
            ArmoredOutputStream arOutStream = new ArmoredOutputStream(outStream);
            arOutStream.setHeader("Version", PgpHelper.getFullVersion(mContext));

            updateProgress(progress * 100 / masterKeyIdsSize, 100);

            try {
                PGPPublicKeyRing publicKeyRing = mProviderHelper.getPGPPublicKeyRing(pubKeyMasterId);

                publicKeyRing.encode(arOutStream);
            } catch (ProviderHelper.NotFoundException e) {
                Log.e(Constants.TAG, "key not found!", e);
                // TODO: inform user?
            }

            if (mApgServiceListener.hasServiceStopped()) {
                arOutStream.close();
                return null;
            }

            arOutStream.close();
        }

        // For each secret masterKey id
        for (long secretKeyMasterId : secretKeyRingMasterIds) {
            progress++;
            // Create an output stream
            ArmoredOutputStream arOutStream = new ArmoredOutputStream(outStream);
            arOutStream.setHeader("Version", PgpHelper.getFullVersion(mContext));

            updateProgress(progress * 100 / masterKeyIdsSize, 100);

            try {
                PGPSecretKeyRing secretKeyRing = mProviderHelper.getPGPSecretKeyRing(secretKeyMasterId);
                secretKeyRing.encode(arOutStream);
            } catch (ProviderHelper.NotFoundException e) {
                Log.e(Constants.TAG, "key not found!", e);
                // TODO: inform user?
            }

            if (mApgServiceListener.hasServiceStopped()) {
                arOutStream.close();
                return null;
            }

            arOutStream.close();
        }

        returnData.putInt(ApgIntentService.RESULT_EXPORT, masterKeyIdsSize);

        updateProgress(R.string.progress_done, 100, 100);

        return returnData;
    }

    @SuppressWarnings("unchecked")
    public int storeKeyRingInCache(PGPKeyRing keyRing) {
        int status = RETURN_ERROR;
        try {
            if (keyRing instanceof PGPSecretKeyRing) {
                PGPSecretKeyRing secretKeyRing = (PGPSecretKeyRing) keyRing;
                boolean save = true;

                for (PGPSecretKey testSecretKey : new IterableIterator<PGPSecretKey>(
                        secretKeyRing.getSecretKeys())) {
                    if (!testSecretKey.isMasterKey()) {
                        if (testSecretKey.isPrivateKeyEmpty()) {
                            // this is bad, something is very wrong...
                            save = false;
                            status = RETURN_BAD;
                        }
                    }
                }

                if (save) {
                    // TODO: preserve certifications
                    // (http://osdir.com/ml/encryption.bouncy-castle.devel/2007-01/msg00054.html ?)
                    PGPPublicKeyRing newPubRing = null;
                    for (PGPPublicKey key : new IterableIterator<PGPPublicKey>(
                            secretKeyRing.getPublicKeys())) {
                        if (newPubRing == null) {
                            newPubRing = new PGPPublicKeyRing(key.getEncoded(),
                                    new JcaKeyFingerprintCalculator());
                        }
                        newPubRing = PGPPublicKeyRing.insertPublicKey(newPubRing, key);
                    }
                    if (newPubRing != null) {
                        mProviderHelper.saveKeyRing(newPubRing);
                    }
                    mProviderHelper.saveKeyRing(secretKeyRing);
                    status = RETURN_OK;
                }
            } else if (keyRing instanceof PGPPublicKeyRing) {
                PGPPublicKeyRing publicKeyRing = (PGPPublicKeyRing) keyRing;
                mProviderHelper.saveKeyRing(publicKeyRing);
                status = RETURN_OK;
            }
        } catch (IOException e) {
            status = RETURN_ERROR;
        }

        return status;
    }

}
