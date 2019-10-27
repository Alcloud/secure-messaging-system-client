package org.thoughtcrime.securesms;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;

import java.io.IOException;
import java.text.ParseException;

public class AuthenticationHelper {

    private static final String TAG = AuthenticationHelper.class.getSimpleName();
    private static final String KEY_FORCE_REAUTH =
            "de.fraunhofer.fokus.ehealth.ask.authenticator.KEY_FORCE_REAUTH";
    private static final String KEY_MIN_REQUIRED_AUTH_LEVEL =
            "de.fraunhofer.fokus.ehealth.ask.authenticator.KEY_MIN_REQUIRED_AUTH_LEVEL";
    private static final String KEY_CLIENTID =
            "de.fraunhofer.fokus.ehealth.ask.authenticator.KEY_CLIENTID";
    private static final String KEY_NONCE =
            "de.fraunhofer.fokus.ehealth.ask.authenticator.KEY_NONCE";
    private static final String KEY_SCOPE =
            "de.fraunhofer.fokus.ehealth.ask.authenticator.KEY_SCOPE";

    private Account mAccount;
    private final AccountManager mAccountManager;
    private final Context mContext;
    private final Activity mActivity;
    private String mAuthToken;
    private boolean mIsCancelReason = false;

    public AuthenticationHelper(Context context, Activity activity, String accountName) {
        this.mContext = context;
        this.mActivity = activity;
        this.mAccountManager = AccountManager.get(mContext);

        this.mAccount = getAccountForName(accountName);
    }

    public String authenticate(String authTokenType, String minAuthLevel, boolean forceReauthenticate,
                               String nonce, String scope, String clientId) {
        mAuthToken = null;
        Log.d(TAG, "authenticate()");
        AccountManagerCallback<Bundle> callback = new AccountManagerCallback<Bundle>() {
            @Override
            public void run(AccountManagerFuture<Bundle> futureManager) {
                // Unless the account creation was cancelled, try logging in again
                // after the account has been created.
                if (!futureManager.isCancelled()) {
                    Log.d(TAG, "futureManager not cancelled");

                    try {
                        Bundle results = futureManager.getResult();
                        if (results != null) {
                            String error = (String) results.get(AccountManager.KEY_ERROR_CODE);
                            if (error != null) Log.d(TAG, error + "\n");
                            String errorMessage = (String) results.get(AccountManager.KEY_ERROR_MESSAGE);
                            if (errorMessage != null) Log.d(TAG,errorMessage + "\n");
                            String accountName = (String) results.get(AccountManager.KEY_ACCOUNT_NAME);
                            if (accountName != null) Log.d(TAG,accountName + "\n");
                            String accountType = (String) results.get(AccountManager.KEY_ACCOUNT_TYPE);
                            if (accountType != null) Log.d(TAG,accountType + "\n");

                            String token = (String) results.get(AccountManager.KEY_AUTHTOKEN);
                            if (token!= null) {
                                mAuthToken = token;
                                //JWT parsedToken = JWTParser.parse(token);
                                //Log.d(TAG,parsedToken.getJWTClaimsSet() + "\n");
                            }
                        }

                    } catch (OperationCanceledException e) {
                        Log.d(TAG,"OperationCanceledException: " + e.getMessage() + "\n");
                        //e.printStackTrace();
                    } catch (IOException e) {
                        Log.d(TAG,"IOException: " + e.getMessage() + "\n");
                        //e.printStackTrace();
                    } catch (AuthenticatorException e) {
                        Log.d(TAG,"AuthenticatorException: " + e.getMessage() + "\n");
                        //e.printStackTrace();
                    }

                } else {
                    Log.d(TAG, "futureManager cancelled");
                }
            }
        };

        Bundle options = new Bundle();
        if (forceReauthenticate) options.putBoolean(KEY_FORCE_REAUTH, forceReauthenticate);
        options.putString(KEY_CLIENTID, clientId);
        if (minAuthLevel != null) options.putString(KEY_MIN_REQUIRED_AUTH_LEVEL, minAuthLevel);
        if (nonce != null) options.putString(KEY_NONCE, nonce);
        if (scope != null) options.putString(KEY_SCOPE, scope);

        mAccountManager.getAuthToken(mAccount, authTokenType,
                options, mActivity, callback, null);

        while (isWaitingForAuthToken()) {
            if (mIsCancelReason) {
                return null;
                // TODO better throw Exception?
            }
        }

        Log.d(TAG, "returning Token");

        return mAuthToken;
    }

    private boolean isWaitingForAuthToken() {

        return mAuthToken == null;
    }

    private Account getAccountForName(String accountName) {
        Account[] accounts = mAccountManager.getAccountsByType("de.fraunhofer.fokus.ehealth.ask.account");
        mAccount = null;
        for (Account account : accounts) {
            if (account.name.equals(accountName))
                return account;
        }

        return null;
    }

    public boolean isOnline() {
        ConnectivityManager connMgr = (ConnectivityManager)
                mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = null;
        if (connMgr != null) {
            networkInfo = connMgr.getActiveNetworkInfo();
        }
        return (networkInfo == null || !networkInfo.isConnected());
    }
}