/*
 * ASTRID: Android's Simple Task Recording Dashboard
 *
 * Copyright (c) 2009 Tim Su
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA
 */
package com.todoroo.astrid.actfm;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ClickableSpan;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.facebook.android.AsyncFacebookRunner;
import com.facebook.android.AsyncFacebookRunner.RequestListener;
import com.facebook.android.AuthListener;
import com.facebook.android.Facebook;
import com.facebook.android.FacebookError;
import com.facebook.android.LoginButton;
import com.facebook.android.Util;
import com.google.android.googlelogin.GoogleLoginServiceConstants;
import com.google.android.googlelogin.GoogleLoginServiceHelper;
import com.timsu.astrid.C2DMReceiver;
import com.timsu.astrid.R;
import com.todoroo.andlib.service.Autowired;
import com.todoroo.andlib.service.ContextManager;
import com.todoroo.andlib.service.DependencyInjectionService;
import com.todoroo.andlib.service.ExceptionService;
import com.todoroo.andlib.utility.DialogUtilities;
import com.todoroo.andlib.utility.Preferences;
import com.todoroo.astrid.actfm.sync.ActFmInvoker;
import com.todoroo.astrid.actfm.sync.ActFmPreferenceService;
import com.todoroo.astrid.actfm.sync.ActFmSyncProvider;
import com.todoroo.astrid.gtasks.auth.ModernAuthManager;
import com.todoroo.astrid.service.AstridDependencyInjector;
import com.todoroo.astrid.service.StatisticsService;
import com.todoroo.astrid.service.TaskService;

/**
 * This activity allows users to sign in or log in to Producteev
 *
 * @author arne.jans
 *
 */
public class ActFmLoginActivity extends Activity implements AuthListener {

    public static final String APP_ID = "183862944961271"; //$NON-NLS-1$

    @Autowired ExceptionService exceptionService;
    @Autowired TaskService taskService;
    @Autowired ActFmPreferenceService actFmPreferenceService;
    private final ActFmInvoker actFmInvoker = new ActFmInvoker();

    private Facebook facebook;
    private AsyncFacebookRunner facebookRunner;
    private TextView errors;
    private boolean noSync = false;

    // --- ui initialization

    private static final int REQUEST_CODE_GOOGLE_ACCOUNTS = 1;
    private static final int REQUEST_CODE_OAUTH = 2;

    static {
        AstridDependencyInjector.initialize();
    }

    public static final String EXTRA_DO_NOT_SYNC = "nosync"; //$NON-NLS-1$

    public ActFmLoginActivity() {
        super();
        DependencyInjectionService.getInstance().inject(this);
    }

    @SuppressWarnings("nls")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ContextManager.setContext(this);

        setContentView(R.layout.actfm_login_activity);
        setTitle(R.string.actfm_ALA_title);

        noSync = getIntent().getBooleanExtra(EXTRA_DO_NOT_SYNC, false);

        facebook = new Facebook(APP_ID);
        facebookRunner = new AsyncFacebookRunner(facebook);

        errors = (TextView) findViewById(R.id.error);
        LoginButton loginButton = (LoginButton) findViewById(R.id.fb_login);
        loginButton.init(this, facebook, this, new String[] {
                "email",
                "offline_access",
                "publish_stream"
        });

        initializeUI();

        getWindow().setFormat(PixelFormat.RGBA_8888);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DITHER);

        StatisticsService.reportEvent("actfm-login-show"); //$NON-NLS-1$

        setResult(RESULT_CANCELED);
    }

    private void initializeUI() {
        findViewById(R.id.gg_login).setOnClickListener(googleListener);
        TextView pwLogin = (TextView) findViewById(R.id.pw_login);
        pwLogin.setOnClickListener(signUpListener);

        String pwLoginBase = getString(R.string.actfm_ALA_pw_login);
        SpannableString link = new SpannableString(String.format("%s %s", //$NON-NLS-1$
                pwLoginBase, getString(R.string.actfm_ALA_pw_link)));
        ClickableSpan linkSpan = new ClickableSpan() {
            @Override
            public void onClick(View widget) {
                signUpListener.onClick(widget);
            }
            @Override
            public void updateDrawState(TextPaint ds) {
                ds.setUnderlineText(true);
                ds.setColor(Color.rgb(255, 96, 0));
            }
        };
        link.setSpan(linkSpan, pwLoginBase.length() + 1, link.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        pwLogin.setText(link);
    }

    // --- event handler

    private final OnClickListener googleListener = new OnClickListener() {
        @Override
        @SuppressWarnings("nls")
        public void onClick(View arg0) {
            Intent intent = new Intent(ActFmLoginActivity.this, OAuthLoginActivity.class);
            try {
                String url = actFmInvoker.createFetchUrl("user_oauth", "provider", "google");
                intent.putExtra(OAuthLoginActivity.URL_TOKEN, url);
                startActivityForResult(intent, REQUEST_CODE_OAUTH);
            } catch (UnsupportedEncodingException e) {
                handleError(e);
            } catch (NoSuchAlgorithmException e) {
                handleError(e);
            }
        }
    };

    private final OnClickListener signUpListener = new OnClickListener() {
        @Override
        public void onClick(View arg0) {
            final LinearLayout body = new LinearLayout(ActFmLoginActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setPadding(10, 0, 10, 0);

            final EditText name = addEditField(body, R.string.actfm_ALA_name_label);

            final AtomicReference<AlertDialog> dialog = new AtomicReference<AlertDialog>();
            final AtomicBoolean isNew = new AtomicBoolean(true);
            final Button toggleNew = new Button(ActFmLoginActivity.this);
            toggleNew.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    isNew.set(!isNew.get());
                    int nameIndex = body.indexOfChild(name);
                    int visibility = isNew.get() ? View.VISIBLE : View.GONE;
                    toggleNew.setText(isNew.get() ? R.string.actfm_ALA_pw_returning :
                        R.string.actfm_ALA_pw_new);
                    dialog.get().setTitle(isNew.get() ? R.string.actfm_ALA_signup_title :
                        R.string.actfm_ALA_login_title);
                    body.getChildAt(nameIndex - 1).setVisibility(visibility);
                    body.getChildAt(nameIndex).setVisibility(visibility);
                }
            });
            toggleNew.setText(R.string.actfm_ALA_pw_returning);
            body.addView(toggleNew, 0);

            final EditText email = addEditField(body, R.string.actfm_ALA_email_label);
            getCredentials(new OnGetCredentials() {
                @Override
                public void getCredentials(String[] accounts) {
                    if(accounts != null && accounts.length > 0)
                        email.setText(accounts[0]);
                }
            });

            final EditText password = addEditField(body, R.string.actfm_ALA_password_label);
            password.setTransformationMethod(new PasswordTransformationMethod());


            dialog.set(new AlertDialog.Builder(ActFmLoginActivity.this)
            .setView(body)
            .setIcon(R.drawable.icon_32)
            .setTitle(R.string.actfm_ALA_signup_title)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dlg, int which) {
                    String nameString = isNew.get() ? name.getText().toString() : null;
                    authenticate(email.getText().toString(), nameString,
                            ActFmInvoker.PROVIDER_PASSWORD, password.getText().toString());

                    if(isNew.get())
                        StatisticsService.reportEvent("actfm-login-pw"); //$NON-NLS-1$
                    else
                        StatisticsService.reportEvent("actfm-signup-pw"); //$NON-NLS-1$
                }
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show());

            dialog.get().setOwnerActivity(ActFmLoginActivity.this);
        }
    };

    private EditText addEditField(LinearLayout body, int hint) {
        TextView label = new TextView(ActFmLoginActivity.this);
        label.setText(hint);
        body.addView(label);
        EditText field = new EditText(ActFmLoginActivity.this);
        field.setHint(hint);
        body.addView(field);
        return field;
    }

    // --- facebook handler

    public void onFBAuthSucceed() {
        createUserAccountFB();
    }

    public void onFBAuthFail(String error) {
        DialogUtilities.okDialog(this, getString(R.string.actfm_ALA_title),
                android.R.drawable.ic_dialog_alert, error, null);
    }

    @Override
    public void onFBAuthCancel() {
        // do nothing
    }

    private ProgressDialog progressDialog;

    /**
     * Create user account via FB
     */
    public void createUserAccountFB() {
        progressDialog = DialogUtilities.progressDialog(this,
                getString(R.string.DLG_please_wait));
        facebookRunner.request("me", new SLARequestListener()); //$NON-NLS-1$
    }

    private class SLARequestListener implements RequestListener {

        @Override
        public void onComplete(String response, Object state) {
            JSONObject json;
            try {
                json = Util.parseJson(response);
                String name = json.getString("name"); //$NON-NLS-1$
                String email = json.getString("email"); //$NON-NLS-1$

                authenticate(email, name, ActFmInvoker.PROVIDER_FACEBOOK,
                        facebook.getAccessToken());
                StatisticsService.reportEvent("actfm-login-fb"); //$NON-NLS-1$
            } catch (FacebookError e) {
                handleError(e);
            } catch (JSONException e) {
                handleError(e);
            }
        }

        @Override
        public void onFacebookError(FacebookError e, Object state) {
            handleError(e);
        }

        @Override
        public void onFileNotFoundException(FileNotFoundException e,
                Object state) {
            handleError(e);
        }

        @Override
        public void onIOException(IOException e, Object state) {
            handleError(e);
        }

        @Override
        public void onMalformedURLException(MalformedURLException e,
                Object state) {
            handleError(e);
        }

    }

    // --- utilities

    public void authenticate(String email, String name, String provider, String secret) {
        if(progressDialog == null)
            progressDialog = DialogUtilities.progressDialog(this, getString(R.string.DLG_please_wait));

        try {
            JSONObject result = actFmInvoker.authenticate(email, name, provider, secret);
            String token = actFmInvoker.getToken();
            postAuthenticate(result, token);
        } catch (IOException e) {
            handleError(e);
        } finally {
            DialogUtilities.dismissDialog(this, progressDialog);
        }
    }

    @SuppressWarnings("nls")
    private void postAuthenticate(JSONObject result, String token) {
        actFmPreferenceService.setToken(token);

        if(Preferences.getStringValue(R.string.actfm_APr_interval_key) == null)
            Preferences.setStringFromInteger(R.string.actfm_APr_interval_key, 3600);

        Preferences.setLong(ActFmPreferenceService.PREF_USER_ID,
                result.optLong("id"));
        Preferences.setString(ActFmPreferenceService.PREF_NAME, result.optString("name"));
        Preferences.setString(ActFmPreferenceService.PREF_EMAIL, result.optString("email"));
        Preferences.setString(ActFmPreferenceService.PREF_PICTURE, result.optString("picture"));

        setResult(RESULT_OK);
        finish();

        if(!noSync) {
            new ActFmSyncProvider().synchronize(this);
        }

        try {
            C2DMReceiver.register();
        } catch (Exception e) {
            // phone may not support c2dm
            exceptionService.reportError("error-c2dm-register", e);
        }
    }

    private void handleError(final Throwable e) {
        DialogUtilities.dismissDialog(this, progressDialog);
        exceptionService.reportError("astrid-sharing-login", e); //$NON-NLS-1$

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                errors.setText(e.getMessage());
                errors.setVisibility(View.VISIBLE);
            }
        });
    }

    // --- google account manager

    @SuppressWarnings("nls")
    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {

        if(resultCode == RESULT_CANCELED)
            return;

        if(requestCode == REQUEST_CODE_GOOGLE_ACCOUNTS){
            String accounts[] = data.getExtras().getStringArray(GoogleLoginServiceConstants.ACCOUNTS_KEY);
            credentialsListener.getCredentials(accounts);
        } else if(requestCode == LoginButton.REQUEST_CODE_FACEBOOK) {
            if(data == null)
                return;

            String error = data.getStringExtra("error");
            if (error == null) {
                error = data.getStringExtra("error_type");
            }
            String token = data.getStringExtra("access_token");
            if(error != null) {
                onFBAuthFail(error);
            } else if(token == null) {
                onFBAuthFail("Something went wrong! Please try again.");
            } else {
                facebook.setAccessToken(token);
                onFBAuthSucceed();
            }
            errors.setVisibility(View.GONE);
        } else if(requestCode == REQUEST_CODE_OAUTH) {
            String result = data.getStringExtra(OAuthLoginActivity.DATA_RESPONSE);
            try {
                JSONObject json = new JSONObject(result);
                postAuthenticate(json, json.getString("token"));
            } catch (JSONException e) {
                handleError(e);
            }
        }
    }
    public interface OnGetCredentials {
        public void getCredentials(String[] accounts);
    }

    private OnGetCredentials credentialsListener;

    public void getCredentials(OnGetCredentials onGetCredentials) {
        credentialsListener = onGetCredentials;
        if(Integer.parseInt(Build.VERSION.SDK) >= 7)
            credentialsListener.getCredentials(ModernAuthManager.getAccounts(this));
        else
            GoogleLoginServiceHelper.getAccount(this, REQUEST_CODE_GOOGLE_ACCOUNTS, false);
    }

}