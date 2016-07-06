/*
 * Created by Itzik Braun on 12/3/2015.
 * Copyright (c) 2015 deluge. All rights reserved.
 *
 * Last Modification at: 3/12/15 4:34 PM
 */

package com.braunster.androidchatsdk.firebaseplugin.firebase;

import android.content.Context;

import com.braunster.androidchatsdk.firebaseplugin.R;
import com.braunster.androidchatsdk.firebaseplugin.firebase.parse.PushUtils;
import com.braunster.androidchatsdk.firebaseplugin.firebase.wrappers.BMessageWrapper;
import com.braunster.androidchatsdk.firebaseplugin.firebase.wrappers.BThreadWrapper;
import com.braunster.androidchatsdk.firebaseplugin.firebase.wrappers.BUserWrapper;
import com.braunster.chatsdk.Utils.Debug;
import com.braunster.chatsdk.dao.BFollower;
import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.dao.BUser;
import com.braunster.chatsdk.dao.ReadReceipt;
import com.braunster.chatsdk.dao.core.DaoCore;
import com.braunster.chatsdk.dao.entities.BMessageEntity;
import com.braunster.chatsdk.dao.entities.BThreadEntity;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BFirebaseDefines;
import com.braunster.chatsdk.object.BError;
import com.firebase.client.AuthData;
import com.firebase.client.DataSnapshot;
import com.firebase.client.Firebase;
import com.firebase.client.FirebaseError;
import com.firebase.client.Query;
import com.firebase.client.ServerValue;
import com.firebase.client.ValueEventListener;

import org.apache.commons.lang3.StringUtils;

import org.jdeferred.Deferred;
import org.jdeferred.DoneCallback;
import org.jdeferred.FailCallback;
import org.jdeferred.ProgressCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;
import org.jdeferred.multiple.MasterDeferredObject;
import org.jdeferred.multiple.MasterProgress;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import timber.log.Timber;

import static com.braunster.chatsdk.network.BDefines.Keys;
import static com.braunster.chatsdk.network.BDefines.Prefs;

public class BChatcatNetworkAdapter extends BFirebaseNetworkAdapter {

    private static final String TAG = BChatcatNetworkAdapter.class.getSimpleName();
    private static boolean DEBUG = Debug.BFirebaseNetworkAdapter;

    public BChatcatNetworkAdapter(Context context){
        super(context);
    }

    public Promise<BUser, BError, Void> handleFAUser(final AuthData authData){
        if (DEBUG) Timber.v("handleFAUser");

        final Deferred<BUser, BError, Void> deferred = new DeferredObject<>();
        
        authingStatus = AuthStatus.HANDLING_F_USER;

        if (authData == null)
        {
            resetAuth();
            // If the user isn't authenticated they'll need to login
            deferred.reject(new BError(BError.Code.SESSION_CLOSED));
        }
        else
        {
            // Flag that the user has been authenticated
            setAuthenticated(true);

            String token = (String) authData.getProviderData().get(Keys.ThirdPartyData.AccessToken);

            String aid = authData.getUid();

            // Save the authentication ID for the current user
            // Set the current user
            final Map<String, Object> loginInfoMap = new HashMap<String, Object>();
            loginInfoMap.put(Prefs.AuthenticationID, aid);
            loginInfoMap.put(Prefs.AccountTypeKey, FirebasePaths.providerToInt(authData.getProvider()));
            loginInfoMap.put(Prefs.TokenKey, token);

            setLoginInfo(loginInfoMap);
            resetAuth();

            // Doint a once() on the user to push its details to firebase.
            final BUserWrapper wrapper = BUserWrapper.initWithAuthData(authData);
            
            wrapper.once().then(new DoneCallback<BUser>() {
                @Override
                public void onDone(BUser bUser) {
                    
                    if (DEBUG) Timber.v("OnDone, user was pulled from firebase.");
                    DaoCore.updateEntity(bUser);

                    getEventManager().userOn(bUser);
                    
                    // TODO push a default image of the user to the cloud.

                    subscribeToPushChannel(wrapper.pushChannel());
                    
                    goOnline();
                    
                    wrapper.push().done(new DoneCallback<BUser>() {
                        @Override
                        public void onDone(BUser u) {

                            if (DEBUG) Timber.v("OnDone, user was pushed from firebase.");
                            resetAuth();
                            deferred.resolve(u);
                        }
                    }).fail(new FailCallback<BError>() {
                        @Override
                        public void onFail(BError error) {
                            resetAuth();
                            deferred.reject(error);
                        }
                    });
                }
            }, new FailCallback<BError>() {
                @Override
                public void onFail(BError bError) {
                    deferred.reject(bError);
                }
            });
        }

        return deferred.promise();

    }

    @Override
    public Promise<BUser, BError, Void> checkUserAuthenticated() {
        if (DEBUG) Timber.v("checkUserAuthenticatedWithCallback, %s", getLoginInfo().get(Prefs.AccountTypeKey));

        final Deferred<BUser, BError, Void> deferred = new DeferredObject<>();

        if (isAuthing())
        {
            if (DEBUG) Timber.d("Already Authing!, Status: %s", authingStatus.name());

            deferred.reject(BError.getError(BError.Code.AUTH_IN_PROCESS, "Cant run two auth in parallel"));
        }
        else
        {
            authingStatus = AuthStatus.CHECKING_IF_AUTH;

            if (!getLoginInfo().containsKey(Prefs.AccountTypeKey))
            {
                if (DEBUG) Timber.d(TAG, "No account type key");

                resetAuth();
                deferred.reject(new BError(BError.Code.NO_LOGIN_INFO));
            }


            Firebase ref = FirebasePaths.firebaseRef();
            if (ref.getAuth()!=null)
            {
                handleFAUser(ref.getAuth()).done(new DoneCallback<BUser>() {
                    @Override
                    public void onDone(BUser bUser) {
                        resetAuth();
                        deferred.resolve(bUser);
                    }
                }).fail(new FailCallback<BError>() {
                    @Override
                    public void onFail(BError bError) {
                        resetAuth();
                        deferred.reject(bError);
                    }
                });
            }
            else{
                resetAuth();
                deferred.reject(BError.getError(BError.Code.NO_AUTH_DATA, "No auth data found"));
            }
        }

        return deferred.promise();
    }

    @Override
    public Promise<Void, BError, Void> changePassword(String email, String oldPassword, String newPassword){
        
        final Deferred<Void, BError, Void> deferred = new DeferredObject<>();
        
        Firebase.ResultHandler resultHandler = new Firebase.ResultHandler(){

            @Override
            public void onSuccess() {
                deferred.resolve(null);
            }

            @Override
            public void onError(FirebaseError firebaseError) {
                deferred.reject(getFirebaseError(firebaseError));
            }
        };

        FirebasePaths.firebaseRef().changePassword(email, oldPassword, newPassword, resultHandler);

        return deferred.promise();
    }

    @Override
    public Promise<Void, BError, Void> sendPasswordResetMail(String email){
        final Deferred<Void, BError, Void> deferred = new DeferredObject<>();
        
        Firebase.ResultHandler resultHandler = new Firebase.ResultHandler(){
            @Override
            public void onSuccess() {
                deferred.resolve(null);
            }

            @Override
            public void onError(FirebaseError firebaseError) {
                deferred.reject(getFirebaseError(firebaseError));
            }
        };

        FirebasePaths.firebaseRef().resetPassword(email, resultHandler);
        
        return deferred.promise();
    }
    
    @Override
    /** Unlike the iOS code the current user need to be saved before you call this method.*/
    public Promise<BUser, BError, Void> pushUser() {
        return currentUser().push();
    }

    @Override
    public BUser currentUserModel() {
        String authID = getCurrentUserAuthenticationId();
        if (StringUtils.isNotEmpty(authID))
        {
            BUser currentUser = DaoCore.fetchEntityWithEntityID(BUser.class, authID);

            if(DEBUG) {
                if (currentUser == null) Timber.e("Current user is null");
                else if (StringUtils.isEmpty(currentUser.getEntityID())) 
                    Timber.e("Current user entity id is null");
            }

            return currentUser;
        }
        if (DEBUG) Timber.e("getCurrentUserAuthenticationIdr is null");
        return null;
    }
    
    public BUserWrapper currentUser(){
        return BUserWrapper.initWithModel(currentUserModel());
    }

    @Override
    public void logout() {

        BUser user = currentUserModel();

        /* No need to logout from facebook due to the fact that the logout from facebook event will trigger this event.
        *  The logout from fb is taking care of by the fb login button.*/
        setAuthenticated(false);

        // Stop listening to user related alerts. (added message or thread.)
        getEventManager().userOff(user);
        
        // Removing the push channel
        if (user != null)
            unsubscribeToPushChannel(user.getPushChannel());

        // Obtaining the simple login object from the ref.
        FirebasePaths ref = FirebasePaths.firebaseRef();

        // Login out
        if (user != null)
        {
            FirebasePaths userOnlineRef = FirebasePaths.userOnlineRef(user.getEntityID());
            userOnlineRef.setValue(false);
        }

        ref.unauth();
    }

    @Override
    public void setUserOnline() {
        BUser current = currentUserModel();
        if (current != null && StringUtils.isNotEmpty(current.getEntityID()))
        {
            currentUser().goOnline();
        }
    }

    @Override
    public void goOffline() {
        Firebase.goOffline();
    }

    @Override
    public void goOnline() {
        Firebase.goOnline();
        
        setUserOnline();
    }

    public Promise<Boolean, BError, Void> isOnline(){

        final Deferred<Boolean, BError, Void> deferred = new DeferredObject<>();

        if (currentUserModel() == null)
        {
            return  deferred.reject(BError.getError(BError.Code.NULL, "Current user is null"));
        }

        FirebasePaths.userOnlineRef(currentUserModel().getEntityID()).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                
                updateLastOnline();
                
                deferred.resolve((Boolean) snapshot.getValue());
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                deferred.reject(getFirebaseError(firebaseError));
            }
        });

        return deferred.promise();
    }
    
    /** Send a message,
     *  The message need to have a owner thread attached to it or it cant be added.
     *  If the destination thread is public the system will add the user to the message thread if needed.
     *  The uploading to the server part can bee seen her {@see BFirebaseNetworkAdapter#PushMessageWithComplition}.*/
    @Override
    public Promise<BMessage, BError, BMessage> sendMessage(final BMessage message){
        if (DEBUG) Timber.v("sendMessage");
        
        return new BMessageWrapper(message).send().done(new DoneCallback<BMessage>() {
            @Override
            public void onDone(BMessage message) {
                // Setting the time stamp for the last message added to the thread.
                FirebasePaths threadRef = FirebasePaths.threadRef(message.getBThreadOwner().getEntityID());
                threadRef = threadRef.appendPathComponent(BFirebaseDefines.Path.BDetailsPath);

                threadRef.updateChildren(FirebasePaths.getMap(new String[]{Keys.BLastMessageAdded}, ServerValue.TIMESTAMP));

                // Pushing the message to all offline users. we cant push it before the message was
                // uploaded as the date is saved by the firebase server using the timestamp.
                pushForMessage(message);
            }
        });
    }

    public  void updateUserReadReceipt(final BMessage message, final BMessage.ReadStatus status){
        BMessageWrapper messageWrapper =  new BMessageWrapper(message);
        messageWrapper.setReadReceipt(status);
    }

    public void readReceiptsOnFromUI(BMessage message){
        new BMessageWrapper(message).readReceiptsOn();
    }

    /** Indexing
     * To allow searching we're going to implement a simple index. Strings can be registered and
     * associated with users i.e. if there's a user called John Smith we could make a new index
     * like this:
     *
     * indexes/[index ID (priority is: johnsmith)]/[entity ID of John Smith]
     *
     * This will allow us to find the user*/
    @Override
    public Promise<List<BUser>, BError, Integer> usersForIndex(final String index, final String value) {
        
        final Deferred<List<BUser>, BError, Integer> deferred = new DeferredObject<>();

        if (StringUtils.isBlank(value))
        {
            return deferred.reject(BError.getError(BError.Code.NULL, "Value is blank"));
        }

        Query query = FirebasePaths.indexRef().orderByChild(index).startAt(
                processForQuery(value)).limitToFirst(BFirebaseDefines.NumberOfUserToLoadForIndex);

        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.getValue() != null) {
                    
                    Map<String, Objects> values = (Map<String, Objects>) snapshot.getValue();

                    final List<BUser> usersToGo = new ArrayList<BUser>();
                    List<String> keys = new ArrayList<String>();

                    // So we dont have to call the db for each key.
                    String currentUserEntityID = currentUserModel().getEntityID();

                    // Adding all keys to the list, Except the current user key.
                    for (String key : values.keySet())
                        if (!key.equals(currentUserEntityID))
                            keys.add(key);

                    // Fetch or create users in the local db.
                    BUser bUser;
                    if (keys.size() > 0) {
                        for (String entityID : keys) {
                            // Making sure that we wont try to get users with a null object id in the index section
                            // If we will try the query will never end and there would be no result from the index.
                            if(StringUtils.isNotBlank(entityID) && !entityID.equals(Keys.BNull) && !entityID.equals("(null)"))
                            {
                                bUser = DaoCore.fetchOrCreateEntityWithEntityID(BUser.class, entityID);
                                usersToGo.add(bUser);
                            }
                        }

                        Promise[] promises = new Promise[keys.size()];
                        
                        int count = 0;
                        for (final BUser user : usersToGo) {
                            
                            final Deferred<BUser, BError, Integer>  d = new DeferredObject<>();

                            BUserWrapper.initWithModel(user)
                                    .once()
                                    .done(new DoneCallback<BUser>() {
                                        @Override
                                        public void onDone(BUser bUser) {
                                            if (DEBUG)
                                                Timber.d("onDone, index: %s, Value: %s", index, value);

                                            // Notify that a user has been found.
                                            // Making sure the user due start with the wanted name
                                            if (processForQuery(bUser.metaStringForKey(index)).startsWith(processForQuery(value))) {
                                                d.resolve(bUser);
                                            } 
                                            else {
                                                if (DEBUG) 
                                                    Timber.d("Not valid result, " +
                                                        "index: %s, UserValue: %s Value: %s", index, bUser.metaStringForKey(index), value);

                                                // Remove the not valid user from the list.
                                                usersToGo.remove(user);

                                                d.resolve(null);
                                            }
                                        }
                                    })
                                    .fail(new FailCallback<BError>() {
                                        @Override
                                        public void onFail(BError bError) {
                                            if (DEBUG) Timber.e("usersForIndex, onDoneWithError.");
                                            // Notify that an error occurred while selecting.
                                            d.reject(bError);
                                        }
                                    });

                            promises[count] = d.promise();
                            count++;
                        }
                        
                        MasterDeferredObject masterDeferredObject = new MasterDeferredObject(promises);
                        
                        masterDeferredObject.progress(new ProgressCallback<MasterProgress>() {
                            @Override
                            public void onProgress(MasterProgress masterProgress) {
                                
                                if (DEBUG) Timber.d("MasterDeferredProgress, done: %s, failed: %s, total: %s", masterProgress.getDone(), masterProgress.getFail(), masterProgress.getTotal());

                                // Reject the promise if all promises failed.
                                if (masterProgress.getFail() == masterProgress.getTotal())
                                {
                                    deferred.reject(BError.getError(BError.Code.OPERATION_FAILED, "All promises failed"));
                                }
                                // If all was done lets resolve the promise.
                                else if (masterProgress.getFail() + masterProgress.getDone() == masterProgress.getTotal())
                                    deferred.resolve(usersToGo);
                            }
                        });

                        
                    } else deferred.reject(BError.getError(BError.Code.NO_USER_FOUND, "Unable to found user."));
                } else {
                    if (DEBUG) Timber.d("Value is null");
                    deferred.reject(BError.getError(BError.Code.NO_USER_FOUND, "Unable to found user."));
                }
            }

            
            @Override
            public void onCancelled(FirebaseError firebaseError) {
                deferred.reject(getFirebaseError(firebaseError));
            }
        });
        
        return deferred.promise();
    }


    @Override
    public Promise<Void, BError, Void> updateIndexForUser(BUser user){
        return BUserWrapper.initWithModel(user).updateIndex();
    }

    @Override
    public Promise<List<BMessage>, Void, Void> loadMoreMessagesForThread(BThread thread) {
        return new BThreadWrapper(thread).loadMoreMessages(BFirebaseDefines.NumberOfMessagesPerBatch);
    }

    @Override
    public Promise<BThread, BError, Void> createPublicThreadWithName(String name) {
        
        final Deferred<BThread, BError, Void> deferred = new DeferredObject<>();

        // Crating the new thread.
        // This thread would not be saved to the local db until it is successfully uploaded to the firebase server.
        final BThread thread = new BThread();

        BUser curUser = currentUserModel();
        thread.setCreator(curUser);
        thread.setCreatorEntityId(curUser.getEntityID());
        thread.setType(BThread.Type.Public);
        thread.setName(name);

        // Add the path and API key
        // This allows you to restrict public threads to a particular
        // API key or root key
        thread.setRootKey(BDefines.BRootPath);
        thread.setApiKey("");
        
        // Save the entity to the local db.
        DaoCore.createEntity(thread);

        BThreadWrapper wrapper = new BThreadWrapper(thread);
        
        wrapper.push()
                .done(new DoneCallback<BThread>() {
                    @Override
                    public void onDone(final BThread thread) {
                        DaoCore.updateEntity(thread);

                        if (DEBUG) Timber.d("public thread is pushed and saved.");

                        // Add the thread to the list of public threads
                        FirebasePaths publicThreadRef = FirebasePaths.publicThreadsRef().appendPathComponent(thread.getEntityID()).appendPathComponent("null");
                        publicThreadRef.setValue("", new Firebase.CompletionListener() {

                            @Override
                            public void onComplete(FirebaseError error, Firebase firebase) {
                                if (error == null)
                                    deferred.resolve(thread);
                                else {
                                    if (DEBUG)
                                        Timber.e("Unable to add thread to public thread ref.");
                                    DaoCore.deleteEntity(thread);
                                    deferred.reject(getFirebaseError(error));
                                }
                            }
                        });
                    }
                })
                .fail(new FailCallback<BError>() {
                    @Override
                    public void onFail(BError error) {
                        if (DEBUG) Timber.e("Failed to push thread to ref.");
                        DaoCore.deleteEntity(thread);
                        deferred.reject(error);
                    }
                });

        return deferred.promise();
    }

    /** Create thread for given users.
     *  When the thread is added to the server the "onMainFinished" will be invoked,
     *  If an error occurred the error object would not be null.
     *  For each user that was successfully added the "onItem" method will be called,
     *  For any item adding failure the "onItemFailed will be called.
     *   If the main task will fail the error object in the "onMainFinished" method will be called."*/
    @Override
    public Promise<BThread, BError, Void> createThreadWithUsers(String name, final List<BUser> users) {

        final Deferred<BThread, BError, Void> deferred = new DeferredObject<>();
        
        BUser currentUser = currentUserModel();

        // Checking to see if this users already has a private thread.
        if (users.size() == 2)
        {
            if (DEBUG) Timber.d("Checking if already has a thread.");
            List<BUser> threadusers;

            BUser userToCheck;
            if (users.get(0).getEntityID().equals(currentUser.getEntityID()))
                userToCheck = users.get(1);
            else userToCheck = users.get(0);

            BThread deletedThreadFound = null;
            for (BThread t : currentUser.getThreads(-1, true))
            {
                // Skipping public threads.
                if (t.getTypeSafely() == BThreadEntity.Type.Public)
                    continue;

                threadusers = t.getUsers();
                if (threadusers.size() == 2) {
                    if (threadusers.get(0).getEntityID().equals(userToCheck.getEntityID()) ||
                            threadusers.get(1).getEntityID().equals(userToCheck.getEntityID())) {

                        // If the thread is deleted we will look for other thread with the user. 
                        // if nothing found we will use the deleted thread and un delete it
                        
                        if (t.isDeleted())
                        {
                            deletedThreadFound = t;
                        }
                        else 
                            return deferred.resolve(t);
                    }
                }
            }
            
            if (deletedThreadFound != null){
                
                new BThreadWrapper(deletedThreadFound).recoverThread();
                
                return deferred.resolve(deletedThreadFound);
            }
        }

        // Didnt find a new thread so we create a new.
        final BThread thread = new BThread();

        thread.setCreator(currentUser);
        thread.setCreatorEntityId(currentUser.getEntityID());

        // If we're assigning users then the thread is always going to be private
        thread.setType(BThread.Type.Private);

        // Save the thread to the database.
        DaoCore.createEntity(thread);

        updateLastOnline();

        new BThreadWrapper(thread).push()
                .done(new DoneCallback<BThread>() {
                    @Override
                    public void onDone(BThread thread) {

                        // Save the thread to the local db.
                        DaoCore.updateEntity(thread);

                        // Add users, For each added user the listener passed here will get a call.
                        addUsersToThread(thread, users).done(new DoneCallback<BThread>() {
                            @Override
                            public void onDone(BThread thread) {
                                deferred.resolve(thread);
                            }
                        })
                        .fail(new FailCallback<BError>() {
                            @Override
                            public void onFail(BError error) {
                                deferred.reject(error);
                            }
                        });
                    }
                })
                .fail(new FailCallback<BError>() {
                    @Override
                    public void onFail(BError error) {
                        // Delete the thread if failed to push
                        DaoCore.deleteEntity(thread);

                        deferred.reject(error);
                    }
                });

        return deferred.promise();
    }

    /** Add given users list to the given thread.
     * The RepetitiveCompletionListenerWithError will notify by his "onItem" method for each user that was successfully added.
     * In the "onItemFailed" you can get all users that the system could not add to the server.
     * When all users are added the system will call the "onDone" method.*/
    @Override
    public Promise<BThread, BError, Void> addUsersToThread(final BThread thread, final List<BUser> users) {
        
        final Deferred<BThread, BError, Void>  deferred = new DeferredObject<>();
                
        if (thread == null)
        {
            if (DEBUG) Timber.e("addUsersToThread, Thread is null" );
            return deferred.reject(new BError(BError.Code.NULL, "Thread is null"));
        }

        if (DEBUG) Timber.d("Users Amount: %s", users.size());

        Promise[] promises = new Promise[users.size()];
        
        BThreadWrapper threadWrapper = new BThreadWrapper(thread);
        
        int count = 0;
        for (BUser user : users){
            
            // Add the user to the thread
            if (!user.hasThread(thread))
            {
                DaoCore.connectUserAndThread(user, thread);
            }
            
            promises[count] = threadWrapper.addUser(BUserWrapper.initWithModel(user));
            count++;
        }
        
        MasterDeferredObject masterDeferredObject = new MasterDeferredObject(promises);
        
        masterDeferredObject.progress(new ProgressCallback<MasterProgress>() {
            @Override
            public void onProgress(MasterProgress masterProgress) {
                if (masterProgress.getFail() + masterProgress.getDone() == masterProgress.getTotal())
                {
                    // Reject the promise if all promisses failed.
                    if (masterProgress.getFail() == masterProgress.getTotal())
                    {
                        deferred.reject(BError.getError(BError.Code.OPERATION_FAILED, "All promises failed"));
                    }
                    else
                        deferred.resolve(thread);
                }
            }
        });
        
        
        return deferred.promise();
    }

    @Override
    public Promise<BThread, BError, Void> removeUsersFromThread(final BThread thread, List<BUser> users) {
        final Deferred<BThread, BError, Void>  deferred = new DeferredObject<>();

        if (thread == null)
        {
            if (DEBUG) Timber.e("addUsersToThread, Thread is null" );
            return deferred.reject(new BError(BError.Code.NULL, "Thread is null"));
        }

        if (DEBUG) Timber.d("Users Amount: %s", users.size());

        Promise[] promises = new Promise[users.size()];

        BThreadWrapper threadWrapper = new BThreadWrapper(thread);

        int count = 0;
        for (BUser user : users){

            // Breaking the connection in the internal database between the thread and the user.
            DaoCore.breakUserAndThread(user, thread);

            promises[count] = threadWrapper.removeUser(BUserWrapper.initWithModel(user));
            count++;
        }

        MasterDeferredObject masterDeferredObject = new MasterDeferredObject(promises);

        masterDeferredObject.progress(new ProgressCallback<MasterProgress>() {
            @Override
            public void onProgress(MasterProgress masterProgress) {
                if (masterProgress.getFail() + masterProgress.getDone() == masterProgress.getTotal())
                {
                    // Reject the promise if all promisses failed.
                    if (masterProgress.getFail() == masterProgress.getTotal())
                    {
                        deferred.reject(null);
                    }
                    else
                        deferred.resolve(thread);
                }
            }
        });
        
        return deferred.promise();
    }

    @Override
    public Promise<BThread, BError, Void>  pushThread(BThread thread) {
        return new BThreadWrapper(thread).push();
    }

    @Override
    public Promise<Void, BError, Void> deleteThreadWithEntityID(final String entityID) {

        final BThread thread = DaoCore.fetchEntityWithEntityID(BThread.class, entityID);

        BUser user = currentUserModel();

        updateLastOnline();
        
        return new BThreadWrapper(thread).deleteThread();
    }







































    @Override
    public Promise<Void, BError, Void> followUser(final BUser userToFollow) {

        if (!BDefines.EnableFollowers)
            throw new IllegalStateException("You need to enable followers in defines before you can use this method.");

        final Deferred<Void, BError, Void> deferred = new DeferredObject<>();
        
        final BUser user = currentUserModel();

        // Add the current user to the userToFollow "followers" path
        FirebasePaths userToFollowRef = FirebasePaths.userRef(userToFollow.getEntityID()).appendPathComponent(BFirebaseDefines.Path.BFollowers).appendPathComponent(user.getEntityID());
        if (DEBUG) Timber.d("followUser, userToFollowRef: ", userToFollowRef.toString());

        userToFollowRef.setValue("null", new Firebase.CompletionListener() {
            @Override
            public void onComplete(FirebaseError firebaseError, Firebase firebase) {
                if (firebaseError!=null)
                {
                    deferred.reject(getFirebaseError(firebaseError));
                }
                else
                {
                    BFollower follows = user.fetchOrCreateFollower(userToFollow, BFollower.Type.FOLLOWS);

                    user.addContact(userToFollow);

                    // Add the user to follow to the current user follow
                    FirebasePaths curUserFollowsRef = FirebasePaths.firebaseRef().appendPathComponent(follows.getBPath().getPath());
                    if (DEBUG) Timber.d("followUser, curUserFollowsRef: %s", curUserFollowsRef.toString());
                    curUserFollowsRef.setValue("null", new Firebase.CompletionListener() {
                        @Override
                        public void onComplete(FirebaseError firebaseError, Firebase firebase) {

                            // Send a push to the user that is now followed.
                            PushUtils.sendFollowPush(userToFollow.getPushChannel(), user.getMetaName() + " " + context.getString(R.string.not_follower_content));

                            deferred.resolve(null);
                        }
                    });
                }
            }
        });

        return deferred.promise();
    }

    @Override
    public void unFollowUser(BUser userToUnfollow) {
        if (!BDefines.EnableFollowers)
            throw new IllegalStateException("You need to enable followers in defines before you can use this method.");


        final BUser user = currentUserModel();

        // Remove the current user to the userToFollow "followers" path
        FirebasePaths userToFollowRef = FirebasePaths.userRef(userToUnfollow.getEntityID()).appendPathComponent(BFirebaseDefines.Path.BFollowers).appendPathComponent(user.getEntityID());

        userToFollowRef.removeValue();

        BFollower follows = user.fetchOrCreateFollower(userToUnfollow, BFollower.Type.FOLLOWS);

        // Add the user to follow to the current user follow
        FirebasePaths curUserFollowsRef = FirebasePaths.firebaseRef().appendPathComponent(follows.getBPath().getPath());

        curUserFollowsRef.removeValue();

        DaoCore.deleteEntity(follows);
    }

    @Override
    public Promise<List<BUser>, BError, Void> getFollowers(String entityId){
        if (DEBUG) Timber.v("getFollowers, Id: %s", entityId);

        final Deferred<List<BUser>, BError, Void> deferred = new DeferredObject<>();
        
        if (StringUtils.isEmpty(entityId))
        {
            return deferred.reject(BError.getError(BError.Code.NULL, "Entity id is empty"));
        }

        final BUser user = DaoCore.fetchOrCreateEntityWithEntityID(BUser.class, entityId);

        FirebasePaths followersRef = FirebasePaths.userFollowersRef(entityId);

        followersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {

                final List<BUser> followers = new ArrayList<BUser>();

                for (DataSnapshot snap : snapshot.getChildren())
                {
                    String followingUserID = snap.getKey();

                    if (StringUtils.isNotEmpty(followingUserID))
                    {
                        BUser follwer = DaoCore.fetchOrCreateEntityWithEntityID(BUser.class, followingUserID);

                        BFollower f = user.fetchOrCreateFollower(follwer, BFollower.Type.FOLLOWER);

                        followers.add(follwer);
                    } else if (DEBUG) Timber.e("Follower id is empty");
                }

                Promise[] promises= new Promise[followers.size()];
                
                int count = 0;
                for (BUser u : followers)
                {
                    promises[count] = BUserWrapper.initWithModel(u).once();

                    count++;
                }
                
                MasterDeferredObject masterDeferredObject = new MasterDeferredObject(promises);

                masterDeferredObject.progress(new ProgressCallback<MasterProgress>() {
                    @Override
                    public void onProgress(MasterProgress masterProgress) {

                        if (DEBUG) Timber.d("MasterDeferredProgress, done: %s, failed: %s, total: %s", masterProgress.getDone(), masterProgress.getFail(), masterProgress.getTotal());

                        // Reject the promise if all promisses failed.
                        if (masterProgress.getFail() == masterProgress.getTotal())
                        {
                            deferred.reject(BError.getError(BError.Code.OPERATION_FAILED, "All promises failed"));
                        }
                        else
                            deferred.resolve(followers);
                    }
                });
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                deferred.reject(getFirebaseError(firebaseError));
            }
        });

        
        return deferred.promise();
    }

    @Override
    public Promise<List<BUser>, BError, Void>  getFollows(String entityId){
        if (DEBUG) Timber.v("getFollowers, Id: %s", entityId);

        final Deferred<List<BUser>, BError, Void> deferred = new DeferredObject<>();

        if (StringUtils.isEmpty(entityId))
        {
            return deferred.reject(BError.getError(BError.Code.NULL, "Entity id is empty"));
        }

        final BUser user = DaoCore.fetchOrCreateEntityWithEntityID(BUser.class, entityId);

        FirebasePaths followersRef = FirebasePaths.userFollowsRef(entityId);

        followersRef.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                final List<BUser> followers = new ArrayList<BUser>();

                for (DataSnapshot snap : snapshot.getChildren())
                {
                    String followingUserID = snap.getKey();

                    if (StringUtils.isNotEmpty(followingUserID))
                    {
                        BUser follwer = DaoCore.fetchOrCreateEntityWithEntityID(BUser.class, followingUserID);

                        BFollower f = user.fetchOrCreateFollower(follwer, BFollower.Type.FOLLOWS);

                        followers.add(follwer);
                    }
                }

                Promise[] promises= new Promise[followers.size()];

                int count = 0;
                for (BUser u : followers)
                {
                    promises[count] = BUserWrapper.initWithModel(u).once();

                    count++;
                }

                MasterDeferredObject masterDeferredObject = new MasterDeferredObject(promises);

                masterDeferredObject.progress(new ProgressCallback<MasterProgress>() {
                    @Override
                    public void onProgress(MasterProgress masterProgress) {

                        if (DEBUG) Timber.d("MasterDeferredProgress, done: %s, failed: %s, total: %s", masterProgress.getDone(), masterProgress.getFail(), masterProgress.getTotal());

                        // Reject the promise if all promisses failed.
                        if (masterProgress.getFail() == masterProgress.getTotal())
                        {
                            deferred.reject(BError.getError(BError.Code.OPERATION_FAILED, "All promises failed"));
                        }
                        else
                            deferred.resolve(followers);
                    }
                });
            }

            @Override
            public void onCancelled(FirebaseError firebaseError) {
                deferred.reject(getFirebaseError(firebaseError));
            }
        });

        return deferred.promise();
    }



    @Override
    public void setLastOnline(Date lastOnline) {
        BUser currentUser  = currentUserModel();
        currentUser.setLastOnline(lastOnline);
        DaoCore.updateEntity(currentUser);

        pushUser();
    }
    
    private void updateLastOnline(){
        // FIXME to implement?
        
    }
}
