package org.thoughtcrime.securesms;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.animation.Animator;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.dd.CircularProgressButton;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import net.sqlcipher.database.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.animation.AnimationCompleteListener;
import org.thoughtcrime.securesms.backup.FullBackupBase;
import org.thoughtcrime.securesms.backup.FullBackupImporter;
import org.thoughtcrime.securesms.components.registration.CallMeCountDownView;
import org.thoughtcrime.securesms.components.registration.VerificationPinKeyboard;
import org.thoughtcrime.securesms.crypto.AttachmentSecretProvider;
import org.thoughtcrime.securesms.crypto.IdentityKeyUtil;
import org.thoughtcrime.securesms.crypto.PreKeyUtil;
import org.thoughtcrime.securesms.crypto.SessionUtil;
import org.thoughtcrime.securesms.database.Address;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.IdentityDatabase;
import org.thoughtcrime.securesms.database.NoExternalStorageException;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.GcmRefreshJob;
import org.thoughtcrime.securesms.lock.RegistrationLockReminders;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.push.AccountManagerFactory;
import org.thoughtcrime.securesms.service.DirectoryRefreshListener;
import org.thoughtcrime.securesms.service.RotateSignedPreKeyListener;
import org.thoughtcrime.securesms.util.BackupUtil;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.Dialogs;
import org.thoughtcrime.securesms.util.PlayServicesUtil;
import org.thoughtcrime.securesms.util.PlayServicesUtil.PlayServicesStatus;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.concurrent.AssertedSuccessListener;
import org.whispersystems.libsignal.IdentityKeyPair;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.KeyHelper;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.push.exceptions.RateLimitException;
import org.whispersystems.signalservice.internal.push.LockedException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The register account activity.  Prompts ths user for their registration information
 * and begins the account registration process.
 */
public class RegistrationActivity extends BaseActionBarActivity {

    private static final int SCENE_TRANSITION_DURATION = 250;
    public static final String CHALLENGE_EVENT = "org.thoughtcrime.securesms.CHALLENGE_EVENT";
    public static final String CHALLENGE_EXTRA = "CAAChallenge";
    public static final String RE_REGISTRATION_EXTRA = "re_registration";
    private boolean isCancelReason = false;
    private static AuthenticationHelper mAuthenticationHelper;
    private String accountName;

    private static final String TAG = RegistrationActivity.class.getSimpleName();

    private TextView number;
    private CircularProgressButton createButton;
    private TextView informationView;
    private TextView informationToggleText;
    private TextView title;
    private TextView subtitle;
    private View registrationContainer;
    private FloatingActionButton fab;

    private View restoreContainer;
    private TextView restoreBackupTime;
    private TextView restoreBackupSize;
    private TextView restoreBackupProgress;
    private CircularProgressButton restoreButton;

    private RegistrationState registrationState;
    private SignalServiceAccountManager accountManager;
    private AccountManager mAccountManager;
    private Account account;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.registration_activity);

        initializeResources();
        initializePermissions();
        initializeNumber();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        markAsVerifying(false);
        EventBus.getDefault().unregister(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (data != null) {
            accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
    }

    private void initializeResources() {
        TextView skipButton = findViewById(R.id.skip_button);
        TextView restoreSkipButton = findViewById(R.id.skip_restore_button);
        View informationToggle = findViewById(R.id.information_link_container);

        this.number = findViewById(R.id.number);
        this.createButton = findViewById(R.id.registerButton);
        this.informationView = findViewById(R.id.registration_information);
        this.informationToggleText = findViewById(R.id.information_label);
        this.title = findViewById(R.id.verify_header);
        this.subtitle = findViewById(R.id.verify_subheader);
        this.registrationContainer = findViewById(R.id.registration_container);
        this.fab = findViewById(R.id.fab);

        this.restoreContainer = findViewById(R.id.restore_container);
        this.restoreBackupSize = findViewById(R.id.backup_size_text);
        this.restoreBackupTime = findViewById(R.id.backup_created_text);
        this.restoreBackupProgress = findViewById(R.id.backup_progress_text);
        this.restoreButton = findViewById(R.id.restore_button);

        this.registrationState = new RegistrationState(RegistrationState.State.INITIAL, null, null, null);

        this.createButton.setOnClickListener(v -> handleRegister());
        skipButton.setOnClickListener(v -> handleCancel());
        informationToggle.setOnClickListener(new InformationToggleListener());

        restoreSkipButton.setOnClickListener(v -> displayInitialView(true));

        if (getIntent().getBooleanExtra(RE_REGISTRATION_EXTRA, false)) {
            skipButton.setVisibility(View.VISIBLE);
        } else {
            skipButton.setVisibility(View.INVISIBLE);
        }

        EventBus.getDefault().register(this);
    }

    @SuppressLint("MissingPermission")
    private void initializeNumber() {
        this.number.setText(TextSecurePreferences.getKVNR(this));
    }

    @SuppressLint("InlinedApi")
    private void initializePermissions() {
        Permissions.with(RegistrationActivity.this)
                .request(Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CONTACTS,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
                        Manifest.permission.PROCESS_OUTGOING_CALLS)
                .ifNecessary()
                .withRationaleDialog(getString(R.string.RegistrationActivity_signal_needs_access_to_your_contacts_and_media_in_order_to_connect_with_friends),
                        R.drawable.ic_contacts_white_48dp, R.drawable.ic_folder_white_48dp)
                .onSomeGranted(permissions -> {
                    if (permissions.contains(Manifest.permission.READ_PHONE_STATE)) {
                        initializeNumber();
                    }

                    if (permissions.contains(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        initializeBackupDetection();
                    }
                })
                .execute();
    }

    @SuppressLint("StaticFieldLeak")
    private void initializeBackupDetection() {
        if (getIntent().getBooleanExtra(RE_REGISTRATION_EXTRA, false)) return;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.CUPCAKE) {
            new AsyncTask<Void, Void, BackupUtil.BackupInfo>() {
                @Override
                protected @Nullable
                BackupUtil.BackupInfo doInBackground(Void... voids) {
                    try {
                        return BackupUtil.getLatestBackup(RegistrationActivity.this);
                    } catch (NoExternalStorageException e) {
                        Log.w(TAG, e);
                        return null;
                    }
                }

                @Override
                protected void onPostExecute(@Nullable BackupUtil.BackupInfo backup) {
                    if (backup != null) displayRestoreView(backup);
                }
            }.execute();
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void handleRestore(BackupUtil.BackupInfo backup) {
        View view = LayoutInflater.from(this).inflate(R.layout.enter_backup_passphrase_dialog, null);
        EditText prompt = view.findViewById(R.id.restore_passphrase_input);

        new AlertDialog.Builder(this)
                .setTitle(R.string.RegistrationActivity_enter_backup_passphrase)
                .setView(view)
                .setPositiveButton(getString(R.string.RegistrationActivity_restore), (dialog, which) -> {
                    InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    inputMethodManager.hideSoftInputFromWindow(prompt.getWindowToken(), 0);

                    restoreButton.setIndeterminateProgressMode(true);
                    restoreButton.setProgress(50);

                    new AsyncTask<Void, Void, Boolean>() {
                        @Override
                        protected Boolean doInBackground(Void... voids) {
                            try {
                                Context context = RegistrationActivity.this;
                                @SuppressLint("WrongThread")
                                String passphrase = prompt.getText().toString();
                                SQLiteDatabase database = DatabaseFactory.getBackupDatabase(context);

                                FullBackupImporter.importFile(context,
                                        AttachmentSecretProvider.getInstance(context).getOrCreateAttachmentSecret(),
                                        database, backup.getFile(), passphrase);

                                DatabaseFactory.upgradeRestored(context, database);

                                TextSecurePreferences.setBackupEnabled(context, true);
                                TextSecurePreferences.setBackupPassphrase(context, passphrase);
                                return true;
                            } catch (IOException e) {
                                Log.w(TAG, e);
                                return false;
                            }
                        }

                        @Override
                        protected void onPostExecute(@NonNull Boolean result) {
                            restoreButton.setIndeterminateProgressMode(false);
                            restoreButton.setProgress(0);
                            restoreBackupProgress.setText("");

                            if (result) {
                                displayInitialView(true);
                            } else {
                                Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_incorrect_backup_passphrase, Toast.LENGTH_LONG).show();
                            }
                        }
                    }.execute();

                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void handleRegister() {
        handleRegisterWithPermissions();
    }

    private void handleRegisterWithPermissions() {

        final String e164number = TextSecurePreferences.getKVNR(this);

        PlayServicesStatus gcmStatus = PlayServicesUtil.getPlayServicesStatus(this);

        if (gcmStatus == PlayServicesStatus.SUCCESS) {
            handleRequestVerification(e164number, true);
        } else if (gcmStatus == PlayServicesStatus.MISSING) {
            handlePromptForNoPlayServices(e164number);
        } else if (gcmStatus == PlayServicesStatus.NEEDS_UPDATE) {
            GoogleApiAvailability.getInstance().getErrorDialog(this, ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED, 0).show();
        } else {
            Dialogs.showAlertDialog(this, getString(R.string.RegistrationActivity_play_services_error),
                    getString(R.string.RegistrationActivity_google_play_services_is_updating_or_unavailable));
        }
    }

    @SuppressLint("StaticFieldLeak")
    private void handleRequestVerification(@NonNull String e164number, boolean gcmSupported) {
        createButton.setIndeterminateProgressMode(true);
        createButton.setProgress(50);
        String password = TextSecurePreferences.getPushServerPassword(this);

        new AsyncTask<Void, Void, Pair<String, Optional<String>>>() {
            @Override
            protected @Nullable
            Pair<String, Optional<String>> doInBackground(Void... voids) {
                try {
                    markAsVerifying(true);

                    //String password = Util.getSecret(18);

                    Optional<String> gcmToken;

                    if (gcmSupported) {
                        gcmToken = Optional.of(GoogleCloudMessaging.getInstance(RegistrationActivity.this).register(GcmRefreshJob.REGISTRATION_ID));
                    } else {
                        gcmToken = Optional.absent();
                    }

                    accountManager = AccountManagerFactory.createManager(RegistrationActivity.this, e164number, password);
                    accountManager.requestSmsVerificationCode();
                    onCodeComplete("123456");

                    return new Pair<>(password, gcmToken);
                } catch (IOException e) {
                    Log.w(TAG, e);
                    return null;
                }
            }

            protected void onPostExecute(@Nullable Pair<String, Optional<String>> result) {
                if (result == null) {
                    Toast.makeText(RegistrationActivity.this, R.string.RegistrationActivity_unable_to_connect_to_service, Toast.LENGTH_LONG).show();
                    return;
                }
                registrationState = new RegistrationState(RegistrationState.State.VERIFYING, e164number, result.first, result.second);
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @SuppressLint("StaticFieldLeak")
    public void onCodeComplete(@NonNull String code) {
        this.registrationState = new RegistrationState(RegistrationState.State.CHECKING, this.registrationState);

        new AsyncTask<Void, Void, Pair<Integer, Long>>() {
            @Override
            protected Pair<Integer, Long> doInBackground(Void... voids) {
                try {
                    verifyAccount(code, null);
                    return new Pair<>(1, -1L);
                } catch (LockedException e) {
                    Log.w(TAG, e);
                    return new Pair<>(2, e.getTimeRemaining());
                } catch (IOException e) {
                    Log.w(TAG, e);
                    return new Pair<>(3, -1L);
                }
            }

            @Override
            protected void onPostExecute(Pair<Integer, Long> result) {
                if (result.first == 1) {
                    handleSuccessfulRegistration();
                } else {
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    private void verifyAccount(@NonNull String code, @Nullable String pin) throws IOException {
        int registrationId = KeyHelper.generateRegistrationId(false);
        TextSecurePreferences.setLocalRegistrationId(RegistrationActivity.this, registrationId);
        SessionUtil.archiveAllSessions(RegistrationActivity.this);

        String signalingKey = Util.getSecret(52);

        String accountName = TextSecurePreferences.getAccountName(RegistrationActivity.this);
        Log.d(TAG, "Account Name: " + accountName);
        mAuthenticationHelper = new AuthenticationHelper(RegistrationActivity.this, this, accountName);

        /*String token = mAuthenticationHelper.authenticate(
                "de.fraunhofer.fokus.ehealth.ask.authenticator.TOKEN_TYPE_ID",
                "session_key", false, null, "profile", "arztmeldung");
        TextSecurePreferences.setPushServerPassword(RegistrationActivity.this, token);
        Log.d(TAG, "REFRESCHTE Token: " + token);*/

        accountManager.verifyAccountWithCode("123456", signalingKey, registrationId, !registrationState.gcmToken.isPresent(), pin);

        IdentityKeyPair identityKey = IdentityKeyUtil.getIdentityKeyPair(RegistrationActivity.this);
        List<PreKeyRecord> records = PreKeyUtil.generatePreKeys(RegistrationActivity.this);
        SignedPreKeyRecord signedPreKey = PreKeyUtil.generateSignedPreKey(RegistrationActivity.this, identityKey, true);

        accountManager.setPreKeys(identityKey.getPublicKey(), signedPreKey, records);

        if (registrationState.gcmToken.isPresent()) {
            accountManager.setGcmId(registrationState.gcmToken);
            Log.d(TAG, "gcm Token: " + registrationState.gcmToken.get());
        }

        TextSecurePreferences.setGcmRegistrationId(RegistrationActivity.this, registrationState.gcmToken.orNull());
        TextSecurePreferences.setGcmDisabled(RegistrationActivity.this, !registrationState.gcmToken.isPresent());
        TextSecurePreferences.setWebsocketRegistered(RegistrationActivity.this, true);

        DatabaseFactory.getIdentityDatabase(RegistrationActivity.this)
                .saveIdentity(Address.fromSerialized(registrationState.e164number),
                        identityKey.getPublicKey(), IdentityDatabase.VerifiedStatus.VERIFIED,
                        true, System.currentTimeMillis(), true);

        TextSecurePreferences.setVerifying(RegistrationActivity.this, false);
        TextSecurePreferences.setPushRegistered(RegistrationActivity.this, true);
        TextSecurePreferences.setKVNR(RegistrationActivity.this, registrationState.e164number);
        //TextSecurePreferences.setPushServerPassword(RegistrationActivity.this, registrationState.password);
        TextSecurePreferences.setSignalingKey(RegistrationActivity.this, signalingKey);
        TextSecurePreferences.setSignedPreKeyRegistered(RegistrationActivity.this, true);
        TextSecurePreferences.setPromptedPushRegistration(RegistrationActivity.this, true);
        TextSecurePreferences.setUnauthorizedReceived(RegistrationActivity.this, false);
    }

    private void handleSuccessfulRegistration() {
        ApplicationContext.getInstance(RegistrationActivity.this).getJobManager().add(new DirectoryRefreshJob(RegistrationActivity.this, false));

        DirectoryRefreshListener.schedule(RegistrationActivity.this);
        RotateSignedPreKeyListener.schedule(RegistrationActivity.this);

        Intent nextIntent = getIntent().getParcelableExtra("next_intent");

        if (nextIntent == null) {
            nextIntent = new Intent(RegistrationActivity.this, ConversationListActivity.class);
        }

        startActivity(nextIntent);
        finish();
    }

    private void displayRestoreView(@NonNull BackupUtil.BackupInfo backup) {
        title.animate().translationX(title.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                title.setText(R.string.RegistrationActivity_restore_from_backup);
                title.clearAnimation();
                title.setTranslationX(-1 * title.getWidth());
                title.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        subtitle.animate().translationX(subtitle.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                subtitle.setText(R.string.RegistrationActivity_restore_your_messages_and_media_from_a_local_backup);
                subtitle.clearAnimation();
                subtitle.setTranslationX(-1 * subtitle.getWidth());
                subtitle.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        registrationContainer.animate().translationX(registrationContainer.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                registrationContainer.clearAnimation();
                registrationContainer.setVisibility(View.INVISIBLE);
                registrationContainer.setTranslationX(0);

                restoreContainer.setTranslationX(-1 * registrationContainer.getWidth());
                restoreContainer.setVisibility(View.VISIBLE);
                restoreButton.setProgress(0);
                restoreButton.setIndeterminateProgressMode(false);
                restoreButton.setOnClickListener(v -> handleRestore(backup));
                restoreBackupSize.setText(getString(R.string.RegistrationActivity_backup_size_s, Util.getPrettyFileSize(backup.getSize())));
                restoreBackupTime.setText(getString(R.string.RegistrationActivity_backup_timestamp_s, DateUtils.getExtendedRelativeTimeSpanString(RegistrationActivity.this, Locale.US, backup.getTimestamp())));
                restoreBackupProgress.setText("");
                restoreContainer.animate().translationX(0).setDuration(SCENE_TRANSITION_DURATION).setListener(null).setInterpolator(new OvershootInterpolator()).start();
            }
        }).start();

        fab.animate().rotationBy(375f).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fab.clearAnimation();
                fab.setImageResource(R.drawable.ic_restore_white_24dp);
                fab.animate().rotationBy(360f).setDuration(SCENE_TRANSITION_DURATION).setListener(null).start();
            }
        }).start();

    }

    private void displayInitialView(boolean forwards) {
        int startDirectionMultiplier = forwards ? -1 : 1;
        int endDirectionMultiplier = forwards ? 1 : -1;

        title.animate().translationX(startDirectionMultiplier * title.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                title.setText(R.string.registration_activity__verify_your_number);
                title.clearAnimation();
                title.setTranslationX(endDirectionMultiplier * title.getWidth());
                title.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        subtitle.animate().translationX(startDirectionMultiplier * subtitle.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                subtitle.setText(R.string.registration_activity__please_enter_your_mobile_number_to_receive_a_verification_code_carrier_rates_may_apply);
                subtitle.clearAnimation();
                subtitle.setTranslationX(endDirectionMultiplier * subtitle.getWidth());
                subtitle.animate().translationX(0).setListener(null).setInterpolator(new OvershootInterpolator()).setDuration(SCENE_TRANSITION_DURATION).start();
            }
        }).start();

        View container;

        container = restoreContainer;

        container.animate().translationX(startDirectionMultiplier * container.getWidth()).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                container.clearAnimation();
                container.setVisibility(View.INVISIBLE);
                container.setTranslationX(0);

                registrationContainer.setTranslationX(endDirectionMultiplier * registrationContainer.getWidth());
                registrationContainer.setVisibility(View.VISIBLE);
                createButton.setProgress(0);
                createButton.setIndeterminateProgressMode(false);
                registrationContainer.animate().translationX(0).setDuration(SCENE_TRANSITION_DURATION).setListener(null).setInterpolator(new OvershootInterpolator()).start();
            }
        }).start();

        fab.animate().rotationBy(startDirectionMultiplier * 360f).setDuration(SCENE_TRANSITION_DURATION).setListener(new AnimationCompleteListener() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fab.clearAnimation();
                fab.setImageResource(R.drawable.ic_action_name);
                fab.animate().rotationBy(startDirectionMultiplier * 375f).setDuration(SCENE_TRANSITION_DURATION).setListener(null).start();
            }
        }).start();
    }

    private void handleCancel() {
        TextSecurePreferences.setPromptedPushRegistration(RegistrationActivity.this, true);
        Intent nextIntent = getIntent().getParcelableExtra("next_intent");

        if (nextIntent == null) {
            nextIntent = new Intent(RegistrationActivity.this, ConversationListActivity.class);
        }

        startActivity(nextIntent);
        finish();
    }

    private void handlePromptForNoPlayServices(@NonNull String e164number) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        dialog.setTitle(R.string.RegistrationActivity_missing_google_play_services);
        dialog.setMessage(R.string.RegistrationActivity_this_device_is_missing_google_play_services);
        dialog.setPositiveButton(R.string.RegistrationActivity_i_understand, (dialog1, which) -> handleRequestVerification(e164number, false));
        dialog.setNegativeButton(android.R.string.cancel, null);
        dialog.show();
    }

    private void markAsVerifying(boolean verifying) {
        TextSecurePreferences.setVerifying(this, verifying);

        if (verifying) {
            TextSecurePreferences.setPushRegistered(this, false);
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(FullBackupBase.BackupEvent event) {
        if (event.getCount() == 0)
            restoreBackupProgress.setText(R.string.RegistrationActivity_checking);
        else
            restoreBackupProgress.setText(getString(R.string.RegistrationActivity_d_messages_so_far, event.getCount()));
    }

    private class InformationToggleListener implements View.OnClickListener {
        @Override
        public void onClick(View v) {
            if (informationView.getVisibility() == View.VISIBLE) {
                informationView.setVisibility(View.GONE);
                informationToggleText.setText(R.string.RegistrationActivity_more_information);
            } else {
                informationView.setVisibility(View.VISIBLE);
                informationToggleText.setText(R.string.RegistrationActivity_less_information);
            }
        }
    }

    private static class RegistrationState {
        private enum State {
            INITIAL, VERIFYING, CHECKING, PIN
        }

        private final State state;
        private final String e164number;
        private final String password;
        private final Optional<String> gcmToken;

        RegistrationState(State state, String e164number, String password, Optional<String> gcmToken) {
            this.state = state;
            this.e164number = e164number;
            this.password = password;
            this.gcmToken = gcmToken;
        }

        RegistrationState(State state, RegistrationState previous) {
            this.state = state;
            this.e164number = previous.e164number;
            this.password = previous.password;
            this.gcmToken = previous.gcmToken;
        }
    }

    private Account getAccountForName(String accountName) {
        Account[] accounts = mAccountManager.getAccountsByType("de.fraunhofer.fokus.ehealth.ask.account");
        account = null;
        for (Account account : accounts) {
            if (account.name.equals(accountName))
                return account;
        }
        return null;
    }
}
