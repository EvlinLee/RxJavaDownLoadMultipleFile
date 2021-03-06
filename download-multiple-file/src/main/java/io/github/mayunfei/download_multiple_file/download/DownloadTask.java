package io.github.mayunfei.download_multiple_file.download;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import io.github.mayunfei.download_multiple_file.db.DownloadDao;
import io.github.mayunfei.download_multiple_file.entity.TaskBundle;
import io.github.mayunfei.download_multiple_file.entity.TaskEntity;
import io.github.mayunfei.download_multiple_file.entity.TaskStatus;
import io.github.mayunfei.download_multiple_file.parser.HtmlParser;
import io.github.mayunfei.download_multiple_file.parser.M3u8Parser;
import io.github.mayunfei.download_multiple_file.utils.IOUtils;
import io.github.mayunfei.download_multiple_file.utils.L;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.List;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * 下载任务
 * Created by yunfei on 17-3-14.
 */
public class DownloadTask implements Runnable {

  private Retrofit retrofit;
  private DownloadDao mDao;
  private TaskBundle mTaskBundle;
  private DownloadApi mDownloadApi;
  private DownloadTaskListener mListener;
  private Call<ResponseBody> download;

  private Handler mHandler = new Handler(Looper.getMainLooper()) {
    @Override public void handleMessage(Message msg) {
      int code = msg.what;
      if (mListener == null) return;
      switch (code) {
        case TaskStatus.STATUS_INIT:
          L.i(mTaskBundle.getKey() + " STATUS_INIT");
          mListener.onConnecting(mTaskBundle);
          break;

        case TaskStatus.STATUS_QUEUE:
          L.i(mTaskBundle.getKey() + " STATUS_QUEUE");
          mListener.onQueue(mTaskBundle);
          break;
        case TaskStatus.STATUS_CONNECTING:
          L.i(mTaskBundle.getKey() + " STATUS_CONNECTING");
          mListener.onConnecting(mTaskBundle);
          break;
        case TaskStatus.STATUS_PAUSE:
          L.i(mTaskBundle.getKey() + " STATUS_PAUSE");
          mListener.onPause(mTaskBundle);
          break;
        case TaskStatus.STATUS_CANCEL:
          L.i(mTaskBundle.getKey() + " STATUS_CANCEL");
          mListener.onCancel(mTaskBundle);
          break;
        case TaskStatus.STATUS_ERROR_NET:
          L.i(mTaskBundle.getKey() + " STATUS_ERROR_NET");
          mListener.onError(mTaskBundle, TaskStatus.STATUS_ERROR_NET);
          break;
        case TaskStatus.STATUS_ERROR_STORAGE:
          L.i(mTaskBundle.getKey() + " STATUS_ERROR_STORAGE");
          mListener.onError(mTaskBundle, TaskStatus.STATUS_ERROR_STORAGE);
          break;
        case TaskStatus.STATUS_FINISHED:
          L.i(mTaskBundle.getKey() + " STATUS_FINISHED");
          mListener.onFinish(mTaskBundle);
          break;
      }
    }
  };

  public void setDownLoadListener(DownloadTaskListener mListener) {
    this.mListener = mListener;
  }

  public TaskBundle getTaskBundle() {
    return mTaskBundle;
  }

  public void setTaskBundle(TaskBundle mTaskBundle) {
    this.mTaskBundle = mTaskBundle;
  }

  @Override public void run() {
    if (isCancel()) {
      return;
    }
    if (!mTaskBundle.isInit()) {
      if (!TextUtils.isEmpty(mTaskBundle.getM3u8())) {
        M3u8Parser m3u8Parser = new M3u8Parser(mDownloadApi, mTaskBundle.getM3u8());
        if (!m3u8Parser.parseTask(mTaskBundle)) {
          //过滤暂停状态
          if (mTaskBundle.getStatus() != TaskStatus.STATUS_PAUSE
              && mTaskBundle.getStatus() != TaskStatus.STATUS_CANCEL) {
            updateStatus(TaskStatus.STATUS_ERROR_NET);
          }
          return;
        }
      }

      //解析html
      if (!TextUtils.isEmpty(mTaskBundle.getHtml())) {
        HtmlParser htmlParser = new HtmlParser(mDownloadApi, mTaskBundle.getHtml());
        if (!htmlParser.parseTask(mTaskBundle)) {
          //过滤暂停状态
          if (mTaskBundle.getStatus() != TaskStatus.STATUS_PAUSE
              && mTaskBundle.getStatus() != TaskStatus.STATUS_CANCEL) {
            updateStatus(TaskStatus.STATUS_ERROR_NET);
          }
          return;
        }
      }
    } else { //防止出现 没有任务的状态
      if (mTaskBundle.getTaskList() == null || mTaskBundle.getTaskList().size() == 0) {
        if (mDao.isExistTaskEntity(mTaskBundle.getBundleId())) {
          List<TaskEntity> taskEntityList =
              mDao.getTaskEntityListByBundleId(mTaskBundle.getBundleId());
          if (mTaskBundle.getTaskList() == null) {
            mTaskBundle.setTaskList(taskEntityList);
          } else {
            mTaskBundle.getTaskList().addAll(taskEntityList);
          }
        }
      }
    }
    //下载列表还是0
    if (mTaskBundle.getTaskList() == null || mTaskBundle.getTaskList().size() == 0) {
      updateStatus(TaskStatus.STATUS_ERROR_NET);
      return;
    }
    mTaskBundle.setTotalSize(mTaskBundle.getTaskList().size());

    //解析完成
    mTaskBundle.setInit(true);
    updateStatus(TaskStatus.STATUS_INIT);
    //开始下载
    List<TaskEntity> taskList = mTaskBundle.getTaskList();
    for (int i = 0; i < taskList.size() && !isCancel(); i++) {
      TaskEntity taskEntity = taskList.get(i);
      //已经下载就不需要下载
      if (taskEntity.isFinish()) continue;

      if (isCancel()) {
        break;
      }
      //真正下载
      downloadFile(taskEntity);

      if (isCancel()) {
        break;
      }
      if (!isCancel() && taskEntity.isFinish()) {
        //完成一部分 刷新UI
        mTaskBundle.setCompleteSize(mTaskBundle.getCompleteSize() + 1);
        updateStatus(TaskStatus.STATUS_CONNECTING);
      }
      if (!isCancel() && mTaskBundle.getCompleteSize() == mTaskBundle.getTotalSize()) {
        //全部下载
        updateStatus(TaskStatus.STATUS_FINISHED);
      }
    }
  }

  private boolean isCancel() {
    return mTaskBundle.getStatus() == TaskStatus.STATUS_PAUSE
        || mTaskBundle.getStatus() == TaskStatus.STATUS_CANCEL
        || mTaskBundle.getStatus() == TaskStatus.STATUS_ERROR_NET
        || mTaskBundle.getStatus() == TaskStatus.STATUS_ERROR_STORAGE;
  }

  private void downloadFile(TaskEntity mTaskEntity) {
    RandomAccessFile tempFile = null;
    InputStream inputStream = null;
    BufferedInputStream bis = null;
    try {

      tempFile =
          new RandomAccessFile(new File(mTaskEntity.getFilePath(), mTaskEntity.getFileName()),
              "rwd");
      Long completeSize = mTaskEntity.getCompletedSize();
      if (tempFile.length() == 0) {
        completeSize = 0L;
      }
      tempFile.seek(completeSize);
      String range = "bytes=" + completeSize + "-";

      download = mDownloadApi.download(range, mTaskEntity.getUrl());

      Response<ResponseBody> response = download.execute();

      if (response.isSuccessful()) {
        ResponseBody responseBody = response.body();
        if (mTaskEntity.getTotalSize() <= 0) { // 第一次下载 total 值是不知道的
          mTaskEntity.setTotalSize(responseBody.contentLength());
        }

        //配置完成 通知UI 开始下载

        double updateSize = mTaskEntity.getTotalSize() / 100;
        inputStream = responseBody.byteStream();
        bis = new BufferedInputStream(inputStream);
        byte[] buffer = new byte[1024];
        int length;
        int buffOffset = 0;
        while ((length = bis.read(buffer)) > 0 && !isCancel()) {
          tempFile.write(buffer, 0, length);
          completeSize += length;
          buffOffset += length;
          mTaskEntity.setCompletedSize(completeSize);
          // 避免一直调用数据库
          if (buffOffset >= updateSize) {
            buffOffset = 0;
            mDao.updateTaskEntity(mTaskEntity);
            L.i("download ing " + mTaskBundle.getKey() + " " + mTaskEntity.getFileName());
            //mDownloadDao.updateTaskEntity(mTaskEntity);
            //handler.sendEmptyMessage(TaskStatus.TASK_STATUS_DOWNLOADING);
          }
        }
        //取消
        if (!isCancel()) {
          //一个完成
          if (mTaskEntity.getCompletedSize() == mTaskEntity.getTotalSize()) {
            mTaskEntity.setFinish(true);
            mDao.updateTaskEntity(mTaskEntity);
          }
        }
      } else {
        // throw Exception
        updateStatus(TaskStatus.STATUS_ERROR_NET);
        mTaskEntity.setFinish(false);
      }
    } catch (IOException e) {
      updateStatus(TaskStatus.STATUS_ERROR_STORAGE);
      e.printStackTrace();
    } finally {
      IOUtils.close(tempFile, inputStream, bis);
    }
  }

  public void updateStatus(int status) {
    mTaskBundle.setStatus(status);
    //switch (status) {
    //  case TaskStatus.STATUS_INIT:
    //  case TaskStatus.STATUS_QUEUE:
    //
    //    break;
    //  case TaskStatus.STATUS_FINISHED:
    //  case TaskStatus.STATUS_PAUSE:
    //  case TaskStatus.STATUS_CONNECTING:
    //  case TaskStatus.STATUS_ERROR_NET:
    //  case TaskStatus.STATUS_ERROR_STORAGE:
    //    mDao.updateTaskBundle(mTaskBundle);
    //    break;
    //}
    mDao.updateTaskBundle(mTaskBundle);
    mHandler.sendEmptyMessage(status);
  }

  public void delete() {

  }

  public void cancle() {
    mTaskBundle.setStatus(TaskStatus.STATUS_CANCEL);
  }

  public void pause() {
    updateStatus(TaskStatus.STATUS_PAUSE);
    if (download != null) {
      download.cancel();
    }
  }

  public void queue() {
    //等待状态
    updateStatus(TaskStatus.STATUS_QUEUE);
  }

  public Retrofit getRetrofit() {
    return retrofit;
  }

  public void setRetrofit(Retrofit retrofit) {
    this.retrofit = retrofit;
  }

  public void setDao(DownloadDao mDao) {
    this.mDao = mDao;
  }

  public DownloadApi getDownloadApi() {
    return mDownloadApi;
  }

  public void setDownloadApi(DownloadApi downloadApi) {
    this.mDownloadApi = downloadApi;
  }

  public void start() {
    mDao.insertTaskBundle(mTaskBundle);
    updateStatus(TaskStatus.STATUS_START);
  }
}
