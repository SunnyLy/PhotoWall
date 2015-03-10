package com.photowall.galis;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
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
    private LruCache<String, Bitmap> mMemoryCache;
    private Set<BitmapLoadTask> mBitmapLoadTaskSet;
    private PhotoAdapter mPhotoAdapter;
    private int mFirstVisibleItem;
    private int mVisibleItemCount;
    private boolean mIsFirstEnter = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mMaxMemory = (int) (Runtime.getRuntime().maxMemory() / 8);
        mMemoryCache = new LruCache<String, Bitmap>(mMaxMemory) {
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                super.entryRemoved(evicted, key, oldValue, newValue);
            }

            @Override
            protected Bitmap create(String key) {
                return super.create(key);
            }

            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount();
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
                if (scrollState == SCROLL_STATE_IDLE) {
                    loadBitmaps(mFirstVisibleItem, mVisibleItemCount);
                } else {
                    cancelAllTasks();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {

                mFirstVisibleItem = firstVisibleItem;
                mVisibleItemCount = visibleItemCount;
                // 下载的任务应该由onScrollStateChanged里调用，但首次进入程序时onScrollStateChanged并不会调用，
                // 因此在这里为首次进入程序开启下载任务。
                if (mIsFirstEnter && visibleItemCount > 0) {
                    loadBitmaps(firstVisibleItem, visibleItemCount);
                    mIsFirstEnter = false;
                }
            }
        });
        mPhotoAdapter = new PhotoAdapter();
        mShowPhotoGridView.setAdapter(mPhotoAdapter);
        initAlbums();
    }

    private void loadBitmaps(int firstVisibleItem, int visibleItemCount) {

        try {
            for (int i = firstVisibleItem; i < firstVisibleItem + visibleItemCount; i++) {

                String imageUrl = mPhotos.get(i);
                Bitmap imageBitmap = mMemoryCache.get(convertToHexKey(imageUrl));
                if (imageBitmap != null) {
                    ((ImageView) mShowPhotoGridView.findViewWithTag(imageUrl)).setImageBitmap(imageBitmap);
                } else {
                    BitmapLoadTask task = new BitmapLoadTask();
                    task.execute(mPhotos.get(i));
                    mBitmapLoadTaskSet.add(task);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

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


    class BitmapLoadTask extends AsyncTask<String, Void, Bitmap> {

        private String imageUrl;

        @Override
        protected Bitmap doInBackground(String... params) {
            imageUrl = params[0];
            Bitmap bitmap = loadNativeFile(imageUrl, mGridItemWidth, mGridItemWidth);
            mMemoryCache.put(convertToHexKey(imageUrl), bitmap);
            return bitmap;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            super.onPostExecute(bitmap);

            ImageView imageView = (ImageView) mShowPhotoGridView.findViewWithTag(imageUrl);
            imageView.setImageBitmap(bitmap);

            mBitmapLoadTaskSet.remove(this);
        }

        @Override
        protected void onCancelled() {
            super.onCancelled();
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

            imageView.setTag(mPhotos.get(position));
            Bitmap bitmap = mMemoryCache.get(convertToHexKey(mPhotos.get(position)));
            if(bitmap!=null){
                imageView.setImageBitmap(bitmap);
            }else {
                imageView.setImageResource(R.drawable.empty_photo);
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
        while (options.outHeight / inSampleSize > height || options.outWidth / inSampleSize > width) {
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
