package com.weifu.app;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class ListActivity     extends AppCompatActivity {

    private ListView listView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.act_scroll_view);
        //2、绑定控件
        listView=(ListView) findViewById(R.id.list_view);
        //3、准备数据
        String[] data={"菠萝","芒果","石榴","葡萄", "苹果", "橙子", "西瓜","菠萝","芒果","石榴","葡萄", "苹果", "橙子", "西瓜","菠萝","芒果","石榴","葡萄", "苹果", "橙子", "西瓜"};
        ArrayAdapter arrayAdapter = new ArrayAdapter(this,R.layout.simple_list_item1,data);
        listView.setAdapter(arrayAdapter);
    }
}
