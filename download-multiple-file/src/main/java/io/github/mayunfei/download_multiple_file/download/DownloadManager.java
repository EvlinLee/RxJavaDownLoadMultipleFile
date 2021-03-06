package io.github.mayunfei.download_multiple_file.download;

import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;
import io.github.mayunfei.download_multiple_file.db.DownloadDao;
import io.github.mayunfei.download_multiple_file.entity.TaskBundle;
import io.github.mayunfei.download_multiple_file.entity.TaskStatus;
import io.github.mayunfei.download_multiple_file.services.DownloadService;
import io.github.mayunfei.download_multiple_file.utils.FileUtils;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import rx.Observable;

/**
 * Created by mayunfei on 17-3-15.
 */

public class DownloadManager {

  private static final int DEF_THREAD_COUNT = 3;
  //请求
  private Retrofit mRetrofit;
  private DownloadApi mDownloadApi;
  // 线程池
  private ThreadPoolExecutor mExecutor;
  private static DownloadManager mInstance;
  private LinkedBlockingQueue<Runnable> mThreadQueue;
  private DownloadDao mDao;
  // task list
  private Map<String, DownloadTask> mCurrentTaskList;
  private int mThreadCount;
  private Context mContext;

  private DownloadManager() {
  }

  public interface TaskListener {
    void onAllTaskFinish();
  }

  public void init(Context context) {
    init(context, DEF_THREAD_COUNT);
  }

  public void init(Context context, int count) {
    init(context, count, null);
  }

  public void init(Context context, int count, Retrofit retrofit) {
    mContext = context.getApplicationContext();
    mDao = new DownloadDao(context.getApplicationContext());
    mDao.pauseAll();
    mThreadCount = count;
    mExecutor = new ThreadPoolExecutor(mThreadCount, mThreadCount, 2000, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<Runnable>());
    mThreadQueue = (LinkedBlockingQueue<Runnable>) mExecutor.getQueue();
    if (retrofit == null) {
      retrofit =
          new Retrofit.Builder().baseUrl("http://blog.csdn.net/zggzcgy/article/details/23987637/")
              .client(getOkHttpClient())
              .build();
    }
    mRetrofit = retrofit;
    //下载api
    mDownloadApi = mRetrofit.create(DownloadApi.class);
    mCurrentTaskList = new LinkedHashMap<>();
  }

  private OkHttpClient getOkHttpClient() {
    return new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build();
  }

  public static synchronized DownloadManager getInstance() {
    if (mInstance == null) {
      mInstance = new DownloadManager();
    }
    return mInstance;
  }

  /**
   * 添加任务
   */
  public void addTaskBundle(@NonNull TaskBundle taskBundle) {
    //插入数据库
    startService();
    DownloadTask currentTask = mCurrentTaskList.get(taskBundle.getKey());
    if (currentTask != null) {
      taskBundle.init(currentTask.getTaskBundle());
      addDownLoadTask(currentTask);
    } else {
      DownloadTask downloadTask = new DownloadTask();
      //配置任务
      downloadTask.setDao(mDao);
      downloadTask.setDownloadApi(mDownloadApi);
      downloadTask.setRetrofit(mRetrofit);
      downloadTask.setTaskBundle(taskBundle);

      addDownLoadTask(downloadTask);
    }
  }

  private void startService() {
    Intent intent = new Intent(mContext, DownloadService.class);
    mContext.startService(intent);
  }

  public void bindListener(@NonNull TaskBundle taskBundle,
      @Nullable DownloadTaskListener downloadTaskListener) {
    bindListener(taskBundle.getKey(), downloadTaskListener);
  }

  public void bindListener(@NonNull String key,
      @Nullable DownloadTaskListener downloadTaskListener) {
    DownloadTask downloadTask = mCurrentTaskList.get(key);
    if (downloadTask == null) {
      Log.e("DownLoadManager", "必须先启动下载才能监听");
    } else {
      downloadTask.setDownLoadListener(downloadTaskListener);
    }
  }

  public Observable<List<TaskBundle>> getObservableAllTaskBundle() {
    return mDao.selectAllTaskBundle();
  }

  public Observable<List<TaskBundle>> getObservableDownloadingBundle() {
    return mDao.getObservableDownloadingBundle();
  }

  private void addDownLoadTask(@NonNull DownloadTask downloadTask) {

    TaskBundle taskBundle = downloadTask.getTaskBundle();
    if (taskBundle == null || taskBundle.getStatus() == TaskStatus.STATUS_FINISHED) return;
    //这里添加是为了让数据库存在数据 并没有放到线程中去
    downloadTask.start();
    mCurrentTaskList.put(taskBundle.getKey(), downloadTask);
    if (!mThreadQueue.contains(downloadTask)) {
      mExecutor.execute(downloadTask);
    }
    if (mExecutor.getTaskCount() > mThreadCount) {
      downloadTask.queue();
    }
  }

  public void pause(String key) {
    DownloadTask downloadTask = mCurrentTaskList.get(key);
    if (downloadTask != null) {
      if (mThreadQueue.contains(downloadTask)) {
        mThreadQueue.remove(downloadTask);
      }
      downloadTask.pause();
    } else {
      Log.e("DownLoadManager", "必须先启动下载才能暂停");
    }
  }

  public void cancel(String key) {
    DownloadTask downloadTask = mCurrentTaskList.get(key);
    if (downloadTask != null) {
      downloadTask.pause();
      //线程中移除
      mThreadQueue.remove(downloadTask);
      mCurrentTaskList.remove(key);
    }
    //io线程删除
    TaskBundle bundle = mDao.getBundleByKey(key);
    if (bundle != null) {
      mDao.deleteBundleByKey(bundle);
      FileUtils.deleteDir(bundle.getFilePath());
    }
  }

  /**
   * 全部暂停
   */
  public void pauseAll() {
    for (DownloadTask downloadTask : mCurrentTaskList.values()) {
      if (downloadTask != null) {
        if (mThreadQueue.contains(downloadTask)) {
          mThreadQueue.remove(downloadTask);
        }
        downloadTask.pause();
      } else {
        Log.e("DownLoadManager", "必须先启动下载才能暂停");
      }
    }
  }

  /**
   * 全部开始
   */
  public void startAll() {
    if (mCurrentTaskList.size() == 0) {
      List<TaskBundle> canDownLoadBundle = mDao.getCanDownLoadBundle();
      for (TaskBundle taskBundle : canDownLoadBundle) {
        addTaskBundle(taskBundle);
      }
    }
    for (DownloadTask downloadTask : mCurrentTaskList.values()) {
      addTaskBundle(downloadTask.getTaskBundle());
    }
  }
}
