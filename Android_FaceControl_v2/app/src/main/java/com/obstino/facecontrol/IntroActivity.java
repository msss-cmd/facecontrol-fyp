package com.obstino.facecontrol;

import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NavUtils;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import android.content.Context;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;

import me.relex.circleindicator.CircleIndicator;

public class IntroActivity extends AppCompatActivity {

    ViewPager viewPager;

    Button button_prev_skip;
    Button button_next_exit;
    int currentPage;
    String pageTitle[] = {
            "About FaceControl",
            "Switches",
            "How to tap",
            "How to drag",
            "How to scroll",
            "Single-switch mode example",
            "Assigning external switches",
            "\"Calibration & test\" menu"
    };
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro);

        int numPages = 8;

        setTitle(pageTitle[0]);

        button_prev_skip = findViewById(R.id.button_prev_skip);
        button_prev_skip.setTextSize(15.0f);
        button_prev_skip.setText("Skip");
        button_prev_skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(currentPage == 0) {
                    // skip the intro tutorial
                    NavUtils.navigateUpFromSameTask(IntroActivity.this);
                } else {
                    // go to previous page
                    viewPager.setCurrentItem(viewPager.getCurrentItem()-1, true);
                }
            }
        });
        
        button_next_exit = findViewById(R.id.button_next_exit);
        button_next_exit.setTextSize(30.0f);
        button_next_exit.setText("►");
        button_next_exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(currentPage == numPages-1) {
                    // exit tutorial
                    NavUtils.navigateUpFromSameTask(IntroActivity.this);
                } else {
                    // go to next page
                    viewPager.setCurrentItem(viewPager.getCurrentItem()+1, true);
                }
            }
        });

        viewPager = findViewById(R.id.viewpager);
        CommonPagerAdapter adapter = new CommonPagerAdapter(this);
        // insert page ids
        adapter.insertViewId(R.id.page_one);
        adapter.insertViewId(R.id.page_two);
        adapter.insertViewId(R.id.page_three);
        adapter.insertViewId(R.id.page_four);
        adapter.insertViewId(R.id.page_five);
        adapter.insertViewId(R.id.page_six);
        adapter.insertViewId(R.id.page_seven);
        adapter.insertViewId(R.id.page_eight);

        // attach adapter to viewpager
        viewPager.setOffscreenPageLimit(numPages);
        viewPager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            Boolean first = true;

            @Override
            public void onPageScrolled(int position, float positionOffset, int positionOffsetPixels) {
                if (first && positionOffset == 0 && positionOffsetPixels == 0){
                    onPageSelected(0);
                    first = false;
                }
            }

            @Override
            public void onPageSelected(int position) {
                View page;
                ImageView ani;

                currentPage = position;
                IntroActivity.this.setTitle(pageTitle[currentPage]);

                if(position == 0) {
                    // Log.i("FaceControl.IntroActivity", "Enabling links!");
                    TextView t = findViewById(R.id.textview_intro);
                    t.setMovementMethod(LinkMovementMethod.getInstance());

                    button_prev_skip.setTextSize(15.0f);
                    button_prev_skip.setText("Skip");
                } else {
                    button_prev_skip.setTextSize(30.0f);
                    button_prev_skip.setText("◄");
                }

                if(position == numPages-1) {
                    button_next_exit.setTextSize(15.0f);
                    button_next_exit.setText("Exit");
                } else {
                    button_next_exit.setTextSize(30.0f);
                    button_next_exit.setText("►");
                }

                switch(position) {
                    case 3-1:
                        page = findViewById(R.id.page_three);
                        ani = page.findViewById(R.id.imageview_ani);
                        Glide.with(IntroActivity.this)
                                .load(R.raw.intro_scan)
                                .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.NONE))
                                .apply(RequestOptions.skipMemoryCacheOf(true))
                                .into(ani);
                        break;
                    case 4-1:
                        page = findViewById(R.id.page_four);
                        ani = page.findViewById(R.id.imageview_ani);
                        Glide.with(IntroActivity.this)
                                .load(R.raw.intro_drag)
                                .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.NONE))
                                .apply(RequestOptions.skipMemoryCacheOf(true))
                                .into(ani);
                        break;
                    case 5-1:
                        page = findViewById(R.id.page_five);
                        ani = page.findViewById(R.id.imageview_ani);
                        Glide.with(IntroActivity.this)
                                .load(R.raw.intro_scroll)
                                .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.NONE))
                                .apply(RequestOptions.skipMemoryCacheOf(true))
                                .into(ani);
                        break;
                    case 6-1:
                        page = findViewById(R.id.page_six);
                        ani = page.findViewById(R.id.imageview_ani);
                        Glide.with(IntroActivity.this)
                                .load(R.raw.intro_singleswitch)
                                .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.NONE))
                                .apply(RequestOptions.skipMemoryCacheOf(true))
                                .into(ani);
                        break;
                    case 7-1:
                        page = findViewById(R.id.page_seven);
                        ani = page.findViewById(R.id.imageview_ani);
                        Glide.with(IntroActivity.this)
                                .load(R.raw.intro_extswitch)
                                .apply(RequestOptions.diskCacheStrategyOf(DiskCacheStrategy.NONE))
                                .apply(RequestOptions.skipMemoryCacheOf(true))
                                .into(ani);
                        break;

                }

                super.onPageSelected(position);
            }
        });
        viewPager.setAdapter(adapter);

        CircleIndicator indicator = (CircleIndicator) findViewById(R.id.indicator);
        indicator.setViewPager(viewPager);
    }
}

class CommonPagerAdapter extends PagerAdapter {
    String TAG = "FaceControl.CommonPageAdapter";

    private List<Integer> pageIds = new ArrayList<>();
    Context context;

    CommonPagerAdapter(Context context) {
        this.context = context;
    }

    public void insertViewId(@IdRes int pageId) {
        pageIds.add(pageId);
    }



    @NonNull
    @Override
    public Object instantiateItem(@NonNull ViewGroup container, int position) {
        Log.i(TAG, "instantiateItem " + position);

        View containerView = container.findViewById(pageIds.get(position));
        /*if(pageIds.get(position) == R.id.page_three) {
            Log.i(TAG, "loading with Glide");
            ImageView imageview_animation = containerView.findViewById(R.id.imageview_ani);
            Glide.with(context).load(R.raw.intro_scan).into(imageview_animation);
        }*/
        return containerView;
    }

    @Override
    public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
        Log.i(TAG, "destroyItem " + position);
        container.removeView((View) object);

        /*View containerView = container.findViewById(pageIds.get(position));
        if(pageIds.get(position) == R.id.page_three) {
            Log.i(TAG, "clearing glide");
            //ImageView imageview_animation = containerView.findViewById(R.id.imageview_animation);
            //Glide.with(context).clear(imageview_animation);
        }*/
    }

    @Override
    public int getCount() {
        return pageIds.size();
    }

    @Override
    public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
        return view == object;
    }
}
