package com.weifu.app.ui.custom;

import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.weifu.app.R;

public class ListActivity     extends AppCompatActivity {

    private ListView listView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.act_scroll_view);
        //2、绑定控件
        listView=(ListView) findViewById(R.id.list_view);
        //3、准备数据
        String[] data={"菠萝","芒果","石榴","葡萄", "苹果", "橙子", "西瓜","菠萝","芒果","石榴","葡萄", "苹果", "橙子", "西瓜","菠萝","芒果","石榴","葡萄", "苹果", "橙子", "西瓜"};
        ArrayAdapter arrayAdapter = new ArrayAdapter(this,R.layout.simple_list_item1,data);
        listView.setAdapter(arrayAdapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                String result=((TextView)view).getText().toString();
                Toast.makeText(ListActivity.this,"您选择的水果是："+result,Toast.LENGTH_LONG).show();
            }
        });
    }
}
