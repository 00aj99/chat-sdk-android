/*
 * Created by Itzik Braun on 12/3/2015.
 * Copyright (c) 2015 deluge. All rights reserved.
 *
 * Last Modification at: 3/12/15 4:34 PM
 */

package com.braunster.androidchatsdk.firebaseplugin.firebase;

import android.content.Context;
import android.graphics.Bitmap;

import com.backendless.Backendless;
import com.backendless.async.callback.AsyncCallback;
import com.backendless.exceptions.BackendlessFault;
import com.braunster.androidchatsdk.firebaseplugin.R;
import com.braunster.androidchatsdk.firebaseplugin.firebase.backendless.BackendlessUtils;
import com.braunster.androidchatsdk.firebaseplugin.firebase.backendless.PushUtils;
import com.braunster.chatsdk.Utils.Debug;
import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.dao.BUser;
import com.braunster.chatsdk.dao.core.DaoCore;
import com.braunster.chatsdk.network.AbstractNetworkAdapter;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BFacebookManager;
import com.braunster.chatsdk.network.BFirebaseDefines;
import com.braunster.chatsdk.network.TwitterManager;
import com.braunster.chatsdk.object.BError;
import com.braunster.chatsdk.object.SaveImageProgress;
import com.firebase.client.AuthData;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.ValueEventListener;
import com.parse.PushService;

import org.jdeferred.Deferred;
import org.jdeferred.DoneCallback;
import org.jdeferred.FailCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import timber.log.Timber;

import static com.braunster.chatsdk.network.BDefines.BAccountType.Anonymous;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Custom;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Facebook;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Password;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Register;
import static com.braunster.chatsdk.network.BDefines.BAccountType.Twitter;
import static com.braunster.chatsdk.network.BDefines.Keys;

public abstract class BFirebaseNetworkAdapter extends AbstractNetworkAdapter {

    private static final String TAG = BFirebaseNetworkAdapter.class.getSimpleName();
    private static boolean DEBUG = Debug.BFirebaseNetworkAdapter;

    public BFirebaseNetworkAdapter(Context context){
        super(context);
        Firebase.setAndroidContext(context);

        // Adding the manager that will handle all the incoming events.
        FirebaseEventsManager eventManager = FirebaseEventsManager.getInstance();
        setEventManager(eventManager);

        // Parse init
        /*Parse.initialize(context, context.getString(R.string.parse_app_id), context.getString(R.string.parse_client_key));
        ParseInstallation.getCurrentInstallation().saveInBackground();*/

        Backendless.initApp(context, context.getString(R.string.backendless_app_id), context.getString(R.string.backendless_secret_key), context.getString(R.string.backendless_app_version));
    }


    /**
     * Indicator for the current state of the authentication process.
     **/
    protected enum AuthStatus{
        IDLE {
            @Override
            public String toString() {
                return "Idle";
            }
        },
        AUTH_WITH_MAP{
            @Override
            public String toString() {
                return "Auth with map";
            }
        },
        HANDLING_F_USER{
            @Override
            public String toString() {
                return "Handling F user";
            }
        },
        UPDATING_USER{
            @Override
            public String toString() {
                return "Updating user";
            }
        },
        PUSHING_USER{
            @Override
            public String toString() {
                return "Pushing user";
            }
        },
        CHECKING_IF_AUTH{
            @Override
            public String toString() {
                return "Checking if Authenticated";
            }
        }
    }

    protected AuthStatus authingStatus = AuthStatus.IDLE;

    public AuthStatus getAuthingStatus() {
        return authingStatus;
    }

    public boolean isAuthing(){
        return authingStatus != AuthStatus.IDLE;
    }

    protected void resetAuth(){
        authingStatus = AuthStatus.IDLE;
    }

    @Override
    public Promise<Object, BError, Void> authenticateWithMap(final Map<String, Object> details) {
        if (DEBUG) Timber.v("authenticateWithMap, KeyType: %s", details.get(BDefines.Prefs.LoginTypeKey));

        final Deferred<Object, BError, Void> deferred = new DeferredObject<>();
        
        if (isAuthing())
        {
            if (DEBUG) Timber.d("Already Authing!, Status: %s", authingStatus.name());
            deferred.reject(BError.getError(BError.Code.AUTH_IN_PROCESS, "Cant run two auth in parallel"));
            return deferred.promise();
        }

        authingStatus = AuthStatus.AUTH_WITH_MAP;

        Firebase ref = FirebasePaths.firebaseRef();
        
        Firebase.AuthResultHandler authResultHandler = new Firebase.AuthResultHandler() {
            @Override
            public void onAuthenticated(final AuthData authData) {
                handleFAUser(authData).then(new DoneCallback<BUser>() {
                    @Override
                    public void onDone(BUser bUser) {
                        resetAuth();
                        deferred.resolve(authData);
                        resetAuth();
                    }
                }, new FailCallback<BError>() {
                    @Override
                    public void onFail(BError bError) {
                        resetAuth();
                        deferred.reject(bError);
                    }
                });
            }

            @Override
            public void onAuthenticationError(FirebaseError firebaseError) {
                if (DEBUG) Timber.e("Error login in, Name: %s", firebaseError.getMessage());
                resetAuth();
                deferred.reject(getFirebaseError(firebaseError));
            }
        };

        switch ((Integer)details.get(BDefines.Prefs.LoginTypeKey))
        {
            case Facebook:

                if (DEBUG) Timber.d(TAG, "authing with fb, AccessToken: %s", BFacebookManager.userFacebookAccessToken);
                ref.authWithOAuthToken("facebook", BFacebookManager.userFacebookAccessToken, authResultHandler);
                break;

            case Twitter:

                Long userId;
                if (details.get(Keys.UserId) instanceof Integer)
                    userId = new Long((Integer) details.get(Keys.UserId));
                else userId = (Long) details.get(Keys.UserId);

                Map<String, String> options = new HashMap<String, String>();

                options.put("oauth_token", TwitterManager.accessToken.getToken());
                options.put("oauth_token_secret", TwitterManager.accessToken.getSecret());

                options.put("user_id", String.valueOf(userId));

                if (DEBUG) Timber.d("authing with twitter. id: %s", userId);

                ref.authWithOAuthToken("twitter", options, authResultHandler);
                break;

            case Password:
                ref.authWithPassword((String) details.get(BDefines.Prefs.LoginEmailKey),
                        (String) details.get(BDefines.Prefs.LoginPasswordKey), authResultHandler);
                break;
            case  Register:
                ref.createUser((String) details.get(BDefines.Prefs.LoginEmailKey),
                        (String) details.get(BDefines.Prefs.LoginPasswordKey), new Firebase.ResultHandler() {
                            @Override
                            public void onSuccess() {
                                
                                // Resetting so we could auth again.
                                resetAuth();
                                
                                //Authing the user after creating it.
                                details.put(BDefines.Prefs.LoginTypeKey, Password);
                                authenticateWithMap(details).done(new DoneCallback<Object>() {
                                    @Override
                                    public void onDone(Object o) {
                                        deferred.resolve(o);
                                    }
                                }).fail(new FailCallback<BError>() {
                                    @Override
                                    public void onFail(BError bError) {
                                        deferred.reject(bError);
                                    }
                                });
                            }

                            @Override
                            public void onError(FirebaseError firebaseError) {
                                if (DEBUG) Timber.e("Error login in, Name: %s", firebaseError.getMessage());
                                resetAuth();
                                deferred.reject(getFirebaseError(firebaseError));
                            }
                        });
                break;

            case Anonymous:
                ref.authAnonymously(authResultHandler);
                break;

            case Custom:
                ref.authWithCustomToken((String) details.get(BDefines.Prefs.TokenKey), authResultHandler);

                break;


            default:
                if (DEBUG) Timber.d("No login type was found");
                deferred.reject(BError.getError(BError.Code.NO_LOGIN_TYPE, "No matching login type was found"));
                break;
        }


        return deferred.promise();
    }

    public abstract Promise<BUser, BError, Void> handleFAUser(final AuthData authData);


    @Override
    public Promise<String[], BError, SaveImageProgress> saveBMessageWithImage(BMessage message) {
        return BackendlessUtils.saveBMessageWithImage(message);
    }

    @Override
    public Promise<String[], BError, SaveImageProgress> saveImageWithThumbnail(String path, int thumbnailSize) {
        return BackendlessUtils.saveImageFileToBackendlessWithThumbnail(path, thumbnailSize);
    }

    @Override
    public Promise<String, BError, SaveImageProgress> saveImage(String path) {
        return BackendlessUtils.saveImageToBackendless(path);
    }

    @Override
    public Promise<String, BError, SaveImageProgress> saveImage(Bitmap b, int size) {
        return BackendlessUtils.saveImageToBackendless(b, size);
    }

    @Override
    public String getServerURL() {
        return BDefines.ServerUrl;
    }

    
    
    protected void pushForMessage(final BMessage message){
        /*if (!parseEnabled())
            return;*/

        if (!backendlessEnabled())
            return;

        if (DEBUG) Timber.v("pushForMessage");
        if (message.getBThreadOwner().getTypeSafely() == BThread.Type.Private) {

            // Loading the message from firebase to get the timestamp from server.
            FirebasePaths firebase = FirebasePaths.threadRef(
                    message.getBThreadOwner().getEntityID())
                    .appendPathComponent(BFirebaseDefines.Path.BMessagesPath)
                    .appendPathComponent(message.getEntityID());

            firebase.addListenerForSingleValueEvent(new ValueEventListener() {
                @Override
                public void onDataChange(DataSnapshot snapshot) {
                    Long date = null;
                    try {
                        date = (Long) snapshot.child(Keys.BDate).getValue();
                    } catch (ClassCastException e) {
                        date = (((Double)snapshot.child(Keys.BDate).getValue()).longValue());
                    }
                    finally {
                        if (date != null)
                        {
                            message.setDate(new Date(date));
                            DaoCore.updateEntity(message);
                        }
                    }

                    // If we failed to get date dont push.
                    if (message.getDate()==null)
                        return;

                    BUser currentUser = currentUserModel();
                    List<BUser> users = new ArrayList<BUser>();

                    for (BUser user : message.getBThreadOwner().getUsers()) {
                        if (!user.equals(currentUser)) {
                            // Timber.v(user.getEntityID() + ", " + user.getOnline().toString());
                            // sends push notification regardless of receiver online status
                            // TODO: add observer to online status
                            // if (user.getOnline() == null || !user.getOnline())
                                users.add(user);
                        }
                    }

                    pushToUsers(message, users);
                }

                @Override
                public void onCancelled(FirebaseError firebaseError) {

                }
            });
        }
    }

    protected void pushToUsers(BMessage message, List<BUser> users){
        if (DEBUG) Timber.v("pushToUsers");

        /*if (!parseEnabled() || users.size() == 0)
            return;*/

        if (!backendlessEnabled() || users.size() == 0)
            return;

        // We're identifying each user using push channels. This means that
        // when a user signs up, they register with backendless on a particular
        // channel. In this case user_[user id] this means that we can
        // send a push to a specific user if we know their user id.
        List<String> channels = new ArrayList<String>();
        for (BUser user : users)
            channels.add(user.getPushChannel());

        PushUtils.sendMessage(message, channels);
    }

    public void subscribeToPushChannel(final String channel){
        /*if (!parseEnabled())
            return;*/

        if (!backendlessEnabled())
            return;

        Backendless.Messaging.registerDevice(context.getString(R.string.google_project_number), channel, new AsyncCallback<Void>() {
            @Override
            public void handleResponse(Void response) {
                if(DEBUG) Timber.v("Device has been subscribed to channel " + channel);
            }

            @Override
            public void handleFault(BackendlessFault fault) {
                if(DEBUG) Timber.v("Device subscription failed. " + fault.getMessage());
            }
        });


        /*try {
            PushService.subscribe(context, channel, ChatSDKUiHelper.getInstance().mainActivity);
        } catch (IllegalArgumentException e) {
            e.printStackTrace();

            if (channel.contains("%3A"))
                PushService.subscribe(context, channel.replace("%3A", "_"), ChatSDKUiHelper.getInstance().mainActivity);
            else if (channel.contains("%253A"))
                PushService.subscribe(context, channel.replace("%253A", "_"), ChatSDKUiHelper.getInstance().mainActivity);
        }*/
    }

    public void unsubscribeToPushChannel(String channel){
        /*if (!parseEnabled())
            return;*/

        if (!backendlessEnabled())
            return;

        // TODO: unsubscribe from push channel backendless
        // http://support.backendless.com/topic/push-notification-unregister-from-a-specific-channel
        Backendless.Messaging.unregisterDevice();

        // PushService.unsubscribe(context, channel);
    }


    /** Convert the firebase error to a {@link com.braunster.chatsdk.object.BError BError} object. */
    public static BError getFirebaseError(FirebaseError error){
        String errorMessage = "";

        int code = 0;

        switch (error.getCode())
        {
            case FirebaseError.EMAIL_TAKEN:
                code = BError.Code.EMAIL_TAKEN;
                errorMessage = "Email is taken.";
                break;

            case FirebaseError.INVALID_EMAIL:
                code = BError.Code.INVALID_EMAIL;
                errorMessage = "Invalid Email.";
                break;

            case FirebaseError.INVALID_PASSWORD:
                code = BError.Code.INVALID_PASSWORD;
                errorMessage = "Invalid Password";
                break;

            case FirebaseError.USER_DOES_NOT_EXIST:
                code = BError.Code.USER_DOES_NOT_EXIST;
                errorMessage = "Account not found.";
                break;

            case FirebaseError.NETWORK_ERROR:
                code = BError.Code.NETWORK_ERROR;
                errorMessage = "Network Error.";
                break;

            case FirebaseError.INVALID_CREDENTIALS:
                code = BError.Code.INVALID_CREDENTIALS;
                errorMessage = "Invalid credentials.";
                break;

            case FirebaseError.EXPIRED_TOKEN:
                code = BError.Code.EXPIRED_TOKEN;
                errorMessage = "Expired Token.";
                break;

            case FirebaseError.OPERATION_FAILED:
                code = BError.Code.OPERATION_FAILED;
                errorMessage = "Operation failed";
                break;

            case FirebaseError.PERMISSION_DENIED:
                code = BError.Code.PERMISSION_DENIED;
                errorMessage = "Permission denied";
                break;

            default: errorMessage = "An Error Occurred.";
        }

        return new BError(code, errorMessage, error);
    }
}
