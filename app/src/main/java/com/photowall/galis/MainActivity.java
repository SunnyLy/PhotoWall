package com.photowall.galis;

import android.content.ContentResolver;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.app.FragmentActivity;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;


public class MainActivity extends FragmentActivity {

    private int mMaxMemory;
    private GridView mShowPhotoGridView;
    private int mGridItemWidth;
    private LinkedList<String> mPhotos;
    private LruCache<String, BitmapDrawable> mMemoryCache;
    private Set<BitmapLoadTask> mBitmapLoadTaskSet;
    private PhotoAdapter mPhotoAdapter;
    private int mFirstVisibleItem;
    private int mVisibleItemCount;
    private boolean mIsFirstEnter = true;
    private Bitmap mLoadingBitmap;

    private final Object mPauseWorkLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMaxMemory = (int) (Runtime.getRuntime().maxMemory() / 4);

        if (BuildConfig.DEBUG) {
            System.out.println(mMaxMemory + "");
        }
        mMemoryCache = new LruCache<String, BitmapDrawable>(mMaxMemory) {
            @Override
            protected void entryRemoved(boolean evicted, String key, BitmapDrawable oldValue, BitmapDrawable newValue) {
                super.entryRemoved(evicted, key, oldValue, newValue);
            }

            @Override
            protected BitmapDrawable create(String key) {
                return super.create(key);
            }

            @Override
            protected int sizeOf(String key, BitmapDrawable bitmapDrawable) {
                return bitmapDrawable.getBitmap().getByteCount();
            }
        };


        DisplayMetrics displayMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);

        mGridItemWidth = (int) ((displayMetrics.widthPixels - 2 * 5 * displayMetrics.density) / 3);

        mBitmapLoadTaskSet = new LinkedHashSet<>();
        mShowPhotoGridView = (GridView) findViewById(R.id.showPhotoGridView);
        mShowPhotoGridView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
//                if (scrollState == SCROLL_STATE_IDLE) {
//                    loadBitmaps(mFirstVisibleItem, mVisibleItemCount);
//                } else {
//                    cancelAllTasks();
//                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {

//                mFirstVisibleItem = firstVisibleItem;
//                mVisibleItemCount = visibleItemCount;
//                // 下载的任务应该由onScrollStateChanged里调用，但首次进入程序时onScrollStateChanged并不会调用，
//                // 因此在这里为首次进入程序开启下载任务。
//                if (mIsFirstEnter && visibleItemCount > 0) {
//                    loadBitmaps(firstVisibleItem, visibleItemCount);
//                    mIsFirstEnter = false;
//                }
            }
        });
        mPhotoAdapter = new PhotoAdapter();
        mShowPhotoGridView.setAdapter(mPhotoAdapter);
        mLoadingBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.empty_photo);
        initAlbums();
    }


    /**
     * Returns true if the current work has been canceled or if there was no work in
     * progress on this image view.
     * Returns false if the work in progress deals with the same data. The work is not
     * stopped in that case.
     */
    public static boolean cancelPotentialWork(String data, ImageView imageView) {
        //BEGIN_INCLUDE(cancel_potential_work)
        final BitmapLoadTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final String bitmapData = bitmapWorkerTask.imageUrl;
            if (bitmapData == null || !bitmapData.equals(data)) {
                bitmapWorkerTask.cancel(true);
            } else {
                // The same work is already in progress.
                return false;
            }
        }
        return true;
        //END_INCLUDE(cancel_potential_work)
    }

    /**
     * @param imageView Any imageView
     * @return Retrieve the currently active work task (if any) associated with this imageView.
     * null if there is no such task.
     */
    private static BitmapLoadTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapLoadTask();
            }
        }
        return null;
    }
//    private void loadBitmaps(int firstVisibleItem, int visibleItemCount) {
//
//        try {
//            for (int i = firstVisibleItem; i < firstVisibleItem + visibleItemCount; i++) {
//
//                String imageUrl = mPhotos.get(i);
//                Bitmap imageBitmap = mMemoryCache.get(convertToHexKey(imageUrl));
//                if (imageBitmap != null) {
//                    ((ImageView) mShowPhotoGridView.findViewWithTag(imageUrl)).setImageBitmap(imageBitmap);
//                } else {
//                    BitmapLoadTask task = new BitmapLoadTask();
//                    task.execute(mPhotos.get(i));
//                    mBitmapLoadTaskSet.add(task);
//                }
//            }
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
//
//    }

    /**
     * 取消所有正在下载或等待下载的任务。
     */

    public void cancelAllTasks() {
        if (mBitmapLoadTaskSet != null) {
            for (BitmapLoadTask task : mBitmapLoadTaskSet) {
                task.cancel(false);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initAlbums() {

        mPhotos = new LinkedList<>();
        ContentResolver contentResolver = getContentResolver();
        Cursor cursor = contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA}, null, null, null);
        cursor.moveToFirst();
        while (cursor.moveToNext()) {
            String path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
            mPhotos.add(path);
        }


        mPhotoAdapter.notifyDataSetChanged();
    }


    class BitmapLoadTask extends AsyncTask<Void, Void, BitmapDrawable> {

        private String imageUrl;
        private WeakReference<ImageView> referImageView;

        public BitmapLoadTask(String url, ImageView imageView) {
            imageUrl = url;
            referImageView = new WeakReference<>(imageView);
        }

        @Override
        protected BitmapDrawable doInBackground(Void... params) {
            BitmapDrawable bitmapDrawable = new BitmapDrawable(loadNativeFile(imageUrl, mGridItemWidth, mGridItemWidth));
            mMemoryCache.put(convertToHexKey(imageUrl), bitmapDrawable);
            return bitmapDrawable;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(BitmapDrawable bitmap) {
            super.onPostExecute(bitmap);
            Drawable bgDrawble = referImageView.get().getDrawable();
            if (bgDrawble instanceof AsyncDrawable) {
                BitmapLoadTask task = ((AsyncDrawable) bgDrawble).getBitmapLoadTask();
                if (this == task) {
                    referImageView.get().setImageDrawable(bitmap);
                }
            }
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
        }
    }

    /**
     * A custom Drawable that will be attached to the imageView while the work is in progress.
     * Contains a reference to the actual worker task, so that it can be stopped if a new binding is
     * required, and makes sure that only the last started worker process can bind its result,
     * independently of the finish order.
     */
    private static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapLoadTask> bitmapWorkerTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, BitmapLoadTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        public BitmapLoadTask getBitmapLoadTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    class PhotoAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mPhotos == null ? 0 : mPhotos.size();
        }

        @Override
        public Object getItem(int position) {
            return mPhotos.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {

            ImageView imageView;
            if (convertView == null) {
                imageView = new ImageView(parent.getContext());
                imageView.setLayoutParams(new ViewGroup.LayoutParams(mGridItemWidth, mGridItemWidth));
                imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            } else {
                imageView = (ImageView) convertView;
            }

            BitmapDrawable bpDrawable = mMemoryCache.get(convertToHexKey(mPhotos.get(position)));
            String imageUrl = mPhotos.get(position);
            if (bpDrawable != null) {
                imageView.setImageDrawable(bpDrawable);
            } else if(cancelPotentialWork(imageUrl,imageView)){
                BitmapLoadTask task = new BitmapLoadTask(imageUrl, imageView);
                AsyncDrawable asynDrawable = new AsyncDrawable(getResources(), mLoadingBitmap, task);
                task.executeOnExecutor(AsyncTask.DUAL_THREAD_EXECUTOR);
                imageView.setImageDrawable(asynDrawable);
            }
            return imageView;
        }
    }

        /**
         * 加载本地图片到内存
         *
         * @param path
         * @param width
         * @param height
         * @return
         */
        private Bitmap loadNativeFile(String path, int width, int height) {

            File image = new File(path);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            if (!image.exists()) {
                throw new NullPointerException(path + "is not exist!!");
            }

            BitmapFactory.decodeFile(path, options);


            int inSampleSize = 1;
            while (options.outHeight / inSampleSize > height && options.outWidth / inSampleSize > width) {
                inSampleSize *= 2;
            }

            options.inSampleSize = inSampleSize;
            options.inJustDecodeBounds = false;

            try {
                return BitmapFactory.decodeStream(new FileInputStream(image), null, options);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            }
            return null;
        }


        private String convertToHexKey(String key) {
            try {
                MessageDigest messageDigest = MessageDigest.getInstance("MD5");
                messageDigest.update(key.getBytes());
                return byteToHexString(messageDigest.digest());
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
            return null;
        }

        private String byteToHexString(byte[] bytes) {
            StringBuilder stringBuilder = new StringBuilder();
            for (int i = 0; i < bytes.length; i++) {
                String hex = Integer.toHexString(0xFF & bytes[i]);
                stringBuilder.append(hex.length() == 1 ? "0" : hex);
            }
            return stringBuilder.toString();
        }
    }
