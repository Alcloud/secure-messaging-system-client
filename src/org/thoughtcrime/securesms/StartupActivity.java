package org.thoughtcrime.securesms;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.ContentProviderOperation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.ContactsContract;
import android.support.annotation.RequiresApi;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;

import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.io.IOException;
import java.text.ParseException;

/**
 * Activity for the first app start, that display registration dialog screen.
 */
public class StartupActivity extends AppCompatActivity implements RegisterEgkDialogFragment.NoticeDialogListener {

    private static final String TAG = StartupActivity.class.getSimpleName();
    private static int ACCOUNT_PICK = 10;

    private FragmentManager fragmentManager;
    private Context mContext;

    private AccountManager mAccountManager;
    private SharedPreferences mSharedPref;
    private SharedPreferences.Editor mEditor;

    private static final String CONTACT_MIMETYPE = "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.messaging";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_startup);

        mContext = this.getBaseContext();
        mAccountManager = AccountManager.get(this.getApplicationContext());
        fragmentManager = getSupportFragmentManager();
        mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);
    }

    @Override
    protected void onResume() {
        super.onResume();

        boolean isRegistered = mSharedPref.getBoolean(getString(R.string.sp_key_isRegistered), false);
        String registerdAccountName = mSharedPref.getString(getString(R.string.sp_key_accountName), null);

        if (isRegistered) {
            if (isExistingAccount(registerdAccountName)) {
                Intent intent = new Intent(mContext, ConversationListActivity.class);
                startActivity(intent);
            }
        }

        // !isRegistered || !isExistingAccount --> start registration
        DialogFragment myFragment = new RegisterEgkDialogFragment();
        myFragment.show(fragmentManager, "RegisterEgkDialogFragment");
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
    }

    @RequiresApi(api = Build.VERSION_CODES.M)
    @Override
    public void onDialogPositiveClick(DialogFragment dialog) {

        Intent intent = AccountManager.newChooseAccountIntent(null,
                null,
                new String[]{"de.fraunhofer.fokus.ehealth.ask.account"},
                "Account mit App verbinden",
                null,
                null,
                null);

        startActivityForResult(intent, ACCOUNT_PICK);

        // User touched the dialog's positive (register) button
        Log.d(TAG, "Register");
    }

    @Override
    public void onDialogNegativeClick(DialogFragment dialog) {
        Log.d(TAG, "Cancel");
        finishAndRemoveTask();
        //ExitActivity.exitApplication(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == ACCOUNT_PICK) {
            Log.d(TAG, "resultcode: " + resultCode);
            if (data != null) {
                String selectedAccount = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
                setAccountAsDefault(selectedAccount);

            } else {
                Log.d(TAG, "Cancelled account selection?!");
                finishAndRemoveTask();
                //ExitActivity.exitApplication(this);
            }
        }
    }

    private void startApplicationActivity() {
        // ok, now we should have an existing account selected
        runOnUiThread(() -> {
            Intent intent = new Intent(mContext, ConversationListActivity.class);
            startActivity(intent);
        });
    }

    private boolean isExistingAccount(String accountName) {
        Account[] accounts = mAccountManager.getAccountsByType("de.fraunhofer.fokus.ehealth.ask.account");
        for (Account account : accounts) {
            if (account.name.equals(accountName))
                return true;
        }

        return false;
    }

    private Account getAccountForName(String accountName) {
        Account[] accounts = mAccountManager.getAccountsByType("de.fraunhofer.fokus.ehealth.ask.account");
        for (Account account : accounts) {
            if (account.name.equals(accountName))
                return account;
        }

        return null;
    }

    private void setAccountAsDefault(String selectedAccount) {

        if (isExistingAccount(selectedAccount)) {
            Account account = getAccountForName(selectedAccount);

            // get registration token
            AccountManagerCallback<Bundle> callback = (AccountManagerFuture<Bundle> futureManager) -> {
                if (!futureManager.isCancelled()) {
                    Log.d(TAG, "futureManager not cancelled");
                    try {
                        Bundle results = futureManager.getResult();
                        if (results != null) {

                            String error = (String) results.get(AccountManager.KEY_ERROR_CODE);
                            if (error != null) {
                                Toast.makeText(mContext, error, Toast.LENGTH_SHORT).show();
                                Log.d(TAG, error);
                            }

                            String errorMessage = (String) results.get(AccountManager.KEY_ERROR_MESSAGE);
                            if (errorMessage != null) {
                                Toast.makeText(mContext, errorMessage, Toast.LENGTH_SHORT).show();
                                Log.d(TAG, errorMessage);
                            }

                            String accountName = (String) results.get(AccountManager.KEY_ACCOUNT_NAME);
                            if (accountName != null) {
                                Log.d(TAG, "Nice, we have an account name: " + accountName);
                            }

                            String accountType = (String) results.get(AccountManager.KEY_ACCOUNT_TYPE);
                            if (accountType != null) {
                                Log.d(TAG, "Nice, we have an account type: " + accountType);
                            }

                            String token = (String) results.get(AccountManager.KEY_AUTHTOKEN);
                            if (token != null) {
                                // This is the happy case, that we are hoping for

                                JWT parsedToken = JWTParser.parse(token);

                                // extract info from eGK or HBA
                                String patientId = parsedToken.getJWTClaimsSet().getStringClaim("uid");
                                //String patientId = "X110382088";
                                //String patientId = parsedToken.getJWTClaimsSet().getStringClaim("patient_id");

                                String givenName = parsedToken.getJWTClaimsSet().getStringClaim("given_name");
                                String familyName = parsedToken.getJWTClaimsSet().getStringClaim("family_name");
                                String fullName = givenName + familyName;

                                // store relevant data to shared prefs
                                Context context = StartupActivity.this;
                                TextSecurePreferences.setPushServerPassword(context, token);
                                TextSecurePreferences.setAccountName(context, accountName);
                                TextSecurePreferences.setProfileName(context, fullName);
                                if (patientId != null){
                                    TextSecurePreferences.setKVNR(context, patientId);
                                    setContactField(context, patientId);
                                }

                                mEditor = mSharedPref.edit();
                                mEditor.putBoolean(getString(R.string.sp_key_isRegistered), true);
                                mEditor.putString(getString(R.string.sp_key_accountName), selectedAccount);
                                mEditor.apply();

                                // now everything is prepared let's start
                                startApplicationActivity();
                            }
                        }
                    } catch (OperationCanceledException | IOException | AuthenticatorException | ParseException e) {
                        e.printStackTrace();
                    }

                } else {
                    Log.d(TAG, "futureManager cancelled");
                }
            };

            mAccountManager.getAuthToken(account, "de.fraunhofer.fokus.ehealth.ask.authenticator.TOKEN_TYPE_REGISTRATION",
                    null, this, callback, null);
        }
    }
    private void setContactField(Context context, String id){
        // Set new contact field
        ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .build())
                .withValue(ContactsContract.Data.MIMETYPE, CONTACT_MIMETYPE)
                .withValue(ContactsContract.Data.DATA1, id)
                .withValue(ContactsContract.Data.DATA2, "messaging")
                .withValue(ContactsContract.Data.DATA3, context.getString(R.string.ContactsDatabase_message_s, id))
                .withYieldAllowed(true)
                .build();
    }
}