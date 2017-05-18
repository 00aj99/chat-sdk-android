package tk.wanderingdevelopment.chatsdk.core.abstracthandlers;

import android.graphics.Bitmap;

import co.chatsdk.core.defines.Debug;
import com.braunster.chatsdk.utils.ImageUtils;
import com.braunster.chatsdk.utils.sorter.ThreadsSorter;
import com.braunster.chatsdk.utils.volley.VolleyUtils;

import com.braunster.chatsdk.dao.BMessage;
import com.braunster.chatsdk.dao.BThread;
import com.braunster.chatsdk.dao.BUser;
import com.braunster.chatsdk.dao.UserThreadLink;
import com.braunster.chatsdk.dao.core.DaoCore;
import com.braunster.chatsdk.dao.entities.BMessageEntity;
import com.braunster.chatsdk.network.BDefines;
import com.braunster.chatsdk.network.BNetworkManager;
import com.braunster.chatsdk.object.ChatError;
import com.google.android.gms.maps.model.LatLng;

import org.apache.commons.lang3.StringUtils;
import org.jdeferred.Deferred;
import org.jdeferred.DoneCallback;
import org.jdeferred.FailCallback;
import org.jdeferred.Promise;
import org.jdeferred.impl.DeferredObject;
import org.joda.time.DateTime;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import co.chatsdk.core.types.ImageUploadResult;
import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.functions.Action;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import timber.log.Timber;
import tk.wanderingdevelopment.chatsdk.core.interfaces.ThreadsInterface;
import tk.wanderingdevelopment.chatsdkcore.db.BThreadDao;

import static com.braunster.chatsdk.dao.entities.BMessageEntity.Type.IMAGE;
import static com.braunster.chatsdk.dao.entities.BMessageEntity.Type.LOCATION;
import static com.braunster.chatsdk.dao.entities.BMessageEntity.Type.TEXT;

/**
 * Created by KyleKrueger on 10.04.2017.
 */

public abstract class ThreadsManager implements ThreadsInterface {

    protected boolean DEBUG = Debug.ThreadsManager;

    public abstract Promise<BMessage, ChatError, BMessage> sendMessage(BMessage message);

    /**
     * Preparing a text message,
     * This is only the build part of the send from here the message will passed to "sendMessage" Method.
     * From there the message will be uploaded to the server if the upload fails the message will be deleted from the local db.
     * If the upload is successful we will update the message entity so the entityId given from the server will be saved.
     * The message will be received before sending in the onMainFinished Callback with a Status that its in the sending process.
     * When the message is fully sent the status will be changed and the onItem callback will be invoked.
     * When done or when an error occurred the calling method will be notified.
     */
    public Promise<BMessage, ChatError, BMessage>  sendMessageWithText(String text, long threadId) {

        final BMessage message = new BMessage();
        message.setText(text);
        message.setThreadId(threadId);
        message.setType(TEXT);
        message.setSender(BNetworkManager.getCoreInterface().currentUserModel());
        message.setStatus(BMessageEntity.Status.SENDING);
        message.setDelivered(BMessageEntity.Delivered.No);

        DaoCore.createEntity(message);

        // Setting the temporary time of the message to be just after the last message that
        // was added to the thread.
        // Using this method we are avoiding time differences between the server time and the
        // device local time.
        Date date = message.getThread().getLastMessageAdded();
        if (date == null)
            date = new Date();

        message.setDate( new DateTime(date.getTime() + 1) );

        DaoCore.updateEntity(message);

        return implSendMessage(message);

        // TODO: What does this do?
//        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
//            @Override
//            public void run() {
//                if(!deferred.isResolved()){
//                    deferred.notify(message);
//                }
//            }
//        }, 100);

    }

    /**
     * Preparing a location message,
     * This is only the build part of the send from here the message will passed to "sendMessage" Method.
     * From there the message will be uploaded to the server if the upload fails the message will be deleted from the local db.
     * If the upload is successful we will update the message entity so the entityId given from the server will be saved.
     * When done or when an error occurred the calling method will be notified.
     *
     * @param filePath     is a String representation of a bitmap that contain the image of the location wanted.
     * @param location       is the Latitude and Longitude of the picked location.
     * @param threadId the id of the thread that the message is sent to.
     */
    public Observable<ImageUploadResult> sendMessageWithLocation(final String filePath, final LatLng location, long threadId) {

        final Deferred<BMessage, ChatError, BMessage> deferred = new DeferredObject<>();

        final BMessage message = new BMessage();
        message.setThreadId(threadId);
        message.setType(LOCATION);
        message.setStatus(BMessageEntity.Status.SENDING);
        message.setDelivered(BMessageEntity.Delivered.No);
        message.setSender(BNetworkManager.getCoreInterface().currentUserModel());
        message.setResourcesPath(filePath);

        DaoCore.createEntity(message);

        // Setting the temporary time of the message to be just after the last message that
        // was added to the thread.
        // Using this method we are avoiding time differences between the server time and the
        // device local time.
        Date date = message.getThread().getLastMessageAdded();
        if (date == null)
            date = new Date();

        message.setDate( new DateTime(date.getTime() + 1) );

        DaoCore.updateEntity(message);

        Bitmap image = ImageUtils.getCompressed(message.getResourcesPath());

        Bitmap thumbnail = ImageUtils.getCompressed(message.getResourcesPath(),
                BDefines.ImageProperties.MAX_IMAGE_THUMBNAIL_SIZE,
                BDefines.ImageProperties.MAX_IMAGE_THUMBNAIL_SIZE);

        message.setImageDimensions(ImageUtils.getDimensionAsString(image));

        return BNetworkManager.getCoreInterface().uploadImage(image, thumbnail).doOnNext(new Consumer<ImageUploadResult>() {
            @Override
            public void accept(ImageUploadResult result) throws Exception {
                if(result.isComplete()) {
                    // Add the LatLng data to the message and the image url and thumbnail url
                    message.setText(String.valueOf(location.latitude)
                            + BDefines.DIVIDER
                            + String.valueOf(location.longitude)
                            + BDefines.DIVIDER + result.imageURL
                            + BDefines.DIVIDER + result.thumbnailURL
                            + BDefines.DIVIDER + message.getImageDimensions());
                }
            }
        }).doOnError(new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                DaoCore.updateEntity(message);

                // Sending the message, After it was uploaded to the server we can delte the file.
                implSendMessage(message)
                        .done(new DoneCallback<BMessage>() {
                            @Override
                            public void onDone(BMessage message) {
                                new File(filePath).delete();
                            }
                        });
            }
        });

    }

    /**
     * Preparing an image message,
     * This is only the build part of the send from here the message will passed to "sendMessage" Method.
     * From there the message will be uploaded to the server if the upload fails the message will be deleted from the local db.
     * If the upload is successful we will update the message entity so the entityId given from the server will be saved.
     * When done or when an error occurred the calling method will be notified.
     *
     * @param filePath is a file that contain the image. For now the file will be decoded to a Base64 image representation.
     * @param threadId the id of the thread that the message is sent to.
     */
    public Observable<ImageUploadResult> sendMessageWithImage(final String filePath, long threadId) {

        final Deferred<BMessage, ChatError, BMessage> deferred = new DeferredObject<>();

        final BMessage message = new BMessage();
        message.setThreadId(threadId);
        message.setType(IMAGE);
        message.setSender(BNetworkManager.getCoreInterface().currentUserModel());
        message.setStatus(BMessageEntity.Status.SENDING);
        message.setDelivered(BMessageEntity.Delivered.No);

        DaoCore.createEntity(message);

        // Setting the temporary time of the message to be just after the last message that
        // was added to the thread.
        // Using this method we are avoiding time differences between the server time and the
        // device local time.
        Date date = message.getThread().getLastMessageAdded();
        if (date == null)
            date = new Date();

        message.setDate(new DateTime(date.getTime() + 1));

        message.setResourcesPath(filePath);

        DaoCore.updateEntity(message);

        Bitmap image = ImageUtils.getCompressed(message.getResourcesPath());

        Bitmap thumbnail = ImageUtils.getCompressed(message.getResourcesPath(),
                BDefines.ImageProperties.MAX_IMAGE_THUMBNAIL_SIZE,
                BDefines.ImageProperties.MAX_IMAGE_THUMBNAIL_SIZE);

        message.setImageDimensions(ImageUtils.getDimensionAsString(image));

        VolleyUtils.getBitmapCache().put(
                VolleyUtils.BitmapCache.getCacheKey(message.getResourcesPath()),
                thumbnail);

        return BNetworkManager.getCoreInterface().uploadImage(image, thumbnail).doOnNext(new Consumer<ImageUploadResult>() {
            @Override
            public void accept(ImageUploadResult result) throws Exception {
                if(result.isComplete()) {
                    message.setText(result.imageURL + BDefines.DIVIDER + result.thumbnailURL + BDefines.DIVIDER + message.getImageDimensions());
                    DaoCore.updateEntity(message);
                }
            }
        }).doOnComplete(new Action() {
            @Override
            public void run() throws Exception {
                implSendMessage(message);
            }
        });

    }

    private Promise<BMessage, ChatError, BMessage> implSendMessage(final BMessage message) {
        return sendMessage(message).done(new DoneCallback<BMessage>() {
            @Override
            public void onDone(BMessage m) {
                m.setStatus(BMessage.Status.SENT);
                DaoCore.updateEntity(m);
            }
        }).fail(new FailCallback<ChatError>() {
            @Override
            public void onFail(ChatError chatError) {
                message.setStatus(BMessage.Status.FAILED);
            }
        });
    }

    public abstract Promise<List<BMessage>, Void, Void> loadMoreMessagesForThread(BThread thread);

    public int getUnreadMessagesAmount(boolean onePerThread){
        List<BThread> threads = BNetworkManager.getThreadsInterface().getThreads(BThread.Type.Private);

        int count = 0;
        for (BThread t : threads)
        {
            if (onePerThread)
            {
                if(!t.isLastMessageWasRead())
                {
                    if (DEBUG) Timber.d("HasUnread, ThreadName: %s", t.displayName());
                    count++;
                }
            }
            else
            {
                count += t.getUnreadMessagesAmount();
            }
        }

        return count;
    }



    /**
     * Create thread for given users.
     * When the thread is added to the server the "onMainFinished" will be invoked,
     * If an error occurred the error object would not be null.
     * For each user that was succesfully added the "onItem" method will be called,
     * For any item adding failure the "onItemFailed will be called.
     * If the main task will fail the error object in the "onMainFinished" method will be called.
     */
    public abstract Promise<BThread, ChatError, Void> createThreadWithUsers(String name, List<BUser> users);

    public Promise<BThread, ChatError, Void> createThreadWithUsers(String name, BUser... users) {
        return createThreadWithUsers(name, Arrays.asList(users));
    }

    public abstract Promise<BThread, ChatError, Void> createPublicThreadWithName(String name);


    public abstract Promise<Void, ChatError, Void> deleteThreadWithEntityID(String entityID);

    public Promise<Void, ChatError, Void> deleteThread(BThread thread){
        return deleteThreadWithEntityID(thread.getEntityID());
    }

    public List<BThread> threadsWithType(int threadType) {

        // Get the thread list ordered desc by the last message added date.
        List<BThread> threadsFromDB;
        BUser currentUser = BNetworkManager.getCoreInterface().currentUserModel();
        BUser threadCreator;

        if (threadType == BThread.Type.Private)
        {
            if (DEBUG) Timber.d("threadItemsWithType, loading private.");
            threadsFromDB = getThreads(BThread.Type.Private);
        }
        else threadsFromDB = DaoCore.fetchEntitiesWithProperty(BThread.class, BThreadDao.Properties.Type, threadType);

        List<BThread> threads = new ArrayList<BThread>();

        if (threadType == BThread.Type.Public)
        {
            for (BThread thread : threadsFromDB)
                if (thread.getTypeSafely() == BThread.Type.Public)
                    threads.add(thread);
        }
        else {
            for (BThread thread : threadsFromDB) {
                if (DEBUG) Timber.i("threadItemsWithType, ThreadID: %s, Deleted: %s", thread.getId(), thread.getDeleted());

                if (thread.isDeleted())
                    continue;

                if (thread.getMessagesWithOrder(DaoCore.ORDER_DESC).size() > 0)
                {
                    threads.add(thread);
                    continue;
                }

                if (StringUtils.isNotBlank(thread.getCreatorEntityId()) && thread.getEntityID().equals(currentUser.getEntityID()))
                {
                    threads.add(thread);
                }
                else
                {
                    threadCreator = thread.getCreator();
                    if (threadCreator != null )
                    {
                        if (threadCreator.equals(currentUser) && thread.hasUser(currentUser))
                        {
                            threads.add(thread);
                        }
                    }
                }
            }
        }

        if (DEBUG) Timber.d("threadsWithType, Type: %s, Found on db: %s, Threads List Size: %s" + threadType, threadsFromDB.size(), threads.size());

        Collections.sort(threads, new ThreadsSorter());

        return threads;
    }

    /**
     * Add given users list to the given thread.
     */
    public abstract Promise<BThread, ChatError, Void> addUsersToThread(BThread thread, List<BUser> users);

    /**
     * Add given users list to the given thread.
     */
    public Promise<BThread, ChatError, Void> addUsersToThread(BThread thread, BUser... users) {
        return addUsersToThread(thread, Arrays.asList(users));
    }

    /**
     * Remove given users list to the given thread.
     */
    public abstract Promise<BThread, ChatError, Void> removeUsersFromThread(BThread thread, List<BUser> users);

    /**
     * Remove given users list to the given thread.
     */
    public Promise<BThread, ChatError, Void> removeUsersFromThread(BThread thread, BUser... users) {
        return removeUsersFromThread(thread, Arrays.asList(users));
    }

    public abstract Promise<BThread, ChatError, Void> pushThread(BThread thread);

    public List<BThread> getThreads(){
        return getThreads(-1);
    }

    public List<BThread> getThreads(int type){
        return getThreads(type, false);
    }

    /**
     * Method updated by Kyle
     *
     * @param type the type of the threads to get, Pass -1 to get all types.
     * @param allowDeleted if true deleted threads will be included in the result list
     * @return a list with all the threads.
     ** */
    public List<BThread> getThreads(int type, boolean allowDeleted){
        List<BThread> bThreads = new ArrayList<>();

        // Freshen up the data by calling reset before getting the list
        List<UserThreadLink> UserThreadLinkList = DaoCore.fetchEntitiesOfClass(UserThreadLink.class);
        // In case the list is empty
        if (UserThreadLinkList == null) return null;
        // Pull the threads out of the link object . . . if only gDao supported manyToMany . . .
        for (UserThreadLink userThreadLink : UserThreadLinkList ){
            if(userThreadLink.getBThread() == null) continue;
            // Do not retrieve deleted threads unless otherwise specified
            if(userThreadLink.getBThread().isDeleted() && !allowDeleted) continue;
            // If the thread type was specified, only add this type
            // TODO: find out why some threads have null types, getTypeSafely should not be needed
            if(userThreadLink.getBThread().getTypeSafely() != type && type != -1) continue;

            bThreads.add(userThreadLink.getBThread());
        }

        // Sort the threads list before returning
        Collections.sort(bThreads, new ThreadsSorter());
        return bThreads;
    }

}
