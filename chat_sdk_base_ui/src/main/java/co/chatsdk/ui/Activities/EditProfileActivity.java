/*
 * Created by Itzik Braun on 2/4/2015.
 * Copyright (c) 2015 deluge. All rights reserved.
 *
 * Last Modification at: 4/2/15 4:25 PM
 */

package co.chatsdk.ui.activities;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.BuildConfig;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import co.chatsdk.core.NM;

import co.chatsdk.core.dao.BUser;
import co.chatsdk.core.dao.DaoDefines;
import co.chatsdk.core.types.Defines;
import co.chatsdk.ui.R;

import com.braunster.chatsdk.network.BFacebookManager;
import com.mukesh.countrypicker.Country;
import com.mukesh.countrypicker.CountryPicker;
import com.mukesh.countrypicker.CountryPickerListener;

import org.apache.commons.lang3.StringUtils;

import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

import co.chatsdk.ui.helpers.UIHelper;
import timber.log.Timber;

/**
 * Created by braunster on 02/04/15.
 */
public class EditProfileActivity extends BaseActivity implements OnClickListener {

    public static final String Male = "male", Female ="female";
    
    private TextView txtMale, txtFemale, txtDateOfBirth;
    
    private EditText etName, etLocation, etStatus;
    
    private ImageView imageCountryFlag;
    
    private boolean loggingOut = false;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        enableCheckOnlineOnResumed(true);
        
        if (getSupportActionBar() != null)
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        
        setContentView(R.layout.chatcat_activity_edit_profile);
        
        initViews();
        
        loadCurrentData();
    }

    private void initViews(){
        txtFemale = (TextView) findViewById(R.id.btn_female);
        txtMale = (TextView) findViewById(R.id.btn_male);
        txtDateOfBirth = (TextView) findViewById(R.id.txt_date_of_birth);
        
        etName = (EditText) findViewById(R.id.chat_sdk_et_name);
        etLocation = (EditText) findViewById(R.id.chat_sdk_et_location);
        etStatus = (EditText) findViewById(R.id.chat_sdk_et_status);

        imageCountryFlag = (ImageView) findViewById(R.id.chat_sdk_country_ic);
    }

    /**
     * Load the user bundle from the database.
     * */
    private void loadCurrentData(){
        BUser user = NM.currentUser();
        
        String gender = user.metaStringForKey(DaoDefines.Keys.Gender);
        
        if (StringUtils.isEmpty(gender) || gender.equals(Male))
        {
            setSelected(txtFemale, false);

            setSelected(txtMale, true);
        }
        else
        {
            setSelected(txtMale, false);

            setSelected(txtFemale, true);
        }
        
        String countryCode = user.metaStringForKey(DaoDefines.Keys.Country);
        
        if (StringUtils.isNotEmpty(countryCode)){
            loadCountryFlag(countryCode);
        }
        
        String name = user.getMetaName();
        String location = user.metaStringForKey(DaoDefines.Keys.Location);
        String dateOfBirth = user.metaStringForKey(DaoDefines.Keys.DateOfBirth);
        String status = user.metaStringForKey(DaoDefines.Keys.Status);

       if (StringUtils.isNotEmpty(name))
           etName.setText(name);

        if (StringUtils.isNotEmpty(location))
            etLocation.setText(location);

        if (StringUtils.isNotEmpty(dateOfBirth))
            txtDateOfBirth.setText(dateOfBirth);

        if (StringUtils.isNotEmpty(status))
            etStatus.setText(status);
    }


    /**
     * The drawable image name has the format "flag_$countryCode". We need to
     * load the drawable dynamically from country code. Code from
     * http://stackoverflow.com/
     * questions/3042961/how-can-i-get-the-resource-id-of
     * -an-image-if-i-know-its-name
     *
     * @param countryCode
     * @return
     */
    public static int getResId(String countryCode) {
        String drawableName = "flag_"
                + countryCode.toLowerCase(Locale.ENGLISH);

        if (BuildConfig.DEBUG) Log.v(Country.class.getSimpleName(), String.format("getResId, Name: %s", drawableName));

        try {
            Class<R.drawable> res = R.drawable.class;
            Field field = res.getField(drawableName);
            int drawableId = field.getInt(null);
            return drawableId;
        } catch (Exception e) {
            e.printStackTrace();
            if (BuildConfig.DEBUG) Log.e(Country.class.getSimpleName(), "cant get the drawable id for country code");
        }
        return -1;
    }

    private void loadCountryFlag(String countryCode){
        imageCountryFlag.setImageResource(this.getResId(countryCode));
        imageCountryFlag.setVisibility(View.VISIBLE);
    }

    /**
     * Save the user details before closing the screen.
     * */
    private void saveDetailsBeforeClose(){
        BUser user = NM.currentUser();

        if (!etName.getText().toString().isEmpty()) {
            user.setMetaName(etName.getText().toString());
        }

        user.setMetadataString(DaoDefines.Keys.DateOfBirth, txtDateOfBirth.getText().toString());

        user.setMetadataString(DaoDefines.Keys.Status, etStatus.getText().toString());

        user.setMetadataString(DaoDefines.Keys.Location, etLocation.getText().toString());
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        txtMale.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {

                if (v.isSelected())
                    return;

                setSelected(txtFemale, false);

                setSelected(txtMale, true);

                NM.currentUser().setMetadataString(DaoDefines.Keys.Gender, "male");
            }
        });

        txtFemale.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {

                if (v.isSelected())
                    return;

                setSelected(txtMale, false);

                setSelected(txtFemale, true);

                NM.currentUser().setMetadataString(DaoDefines.Keys.Gender, "female");
            }
        });

        findViewById(R.id.chat_sdk_logout_button).setOnClickListener(this);
        findViewById(R.id.chat_sdk_app_info_button).setOnClickListener(this);
        findViewById(R.id.chat_sdk_select_country_button).setOnClickListener(this);
        findViewById(R.id.chat_sdk_pick_birth_date_button).setOnClickListener(this);
    }

    public void logout() {
        // Logout and return to the login activity.
        BFacebookManager.logout(this);

        NM.auth().logout();
        UIHelper.getInstance().startLoginActivity(true);
    }
    
    private void setSelected(TextView textView, boolean selected){
        
        textView.setSelected(selected);
        
        if (selected)
            textView.setTextColor(getResources().getColor(R.color.white));
        else
            textView.setTextColor(getResources().getColor(R.color.dark_gray));

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        
        if (item.getItemId() == android.R.id.home)
        {
            onBackPressed();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        
        if (!loggingOut)
        {
            saveDetailsBeforeClose();
            NM.core().pushUser();
        }


        overridePendingTransition(R.anim.dummy, R.anim.slide_top_bottom_out);
    }


    @Override
    public void onClick(View v) {
        int i = v.getId();
        if (i == R.id.chat_sdk_logout_button) {
            loggingOut = true;
            logout();
        }
        else if (i == R.id.chat_sdk_app_info_button) {
            try {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);

                startActivity(intent);
            }
            catch (Exception e)
            {
                Timber.e(e.getCause(), getString(R.string.unable_to_open_app_in_settings));
                UIHelper.getInstance().showToast(R.string.unable_to_open_app_in_settings);
            }

        }
        else if (i == R.id.chat_sdk_pick_birth_date_button) {
            final Calendar calendar = Calendar.getInstance();

            DatePickerDialog datePickerDialog = new DatePickerDialog(EditProfileActivity.this, new DatePickerDialog.OnDateSetListener() {
                @Override
                public void onDateSet(DatePicker view, int year, int monthOfYear, int dayOfMonth) {

                    calendar.set(year, monthOfYear, dayOfMonth);

                    txtDateOfBirth.setText(new SimpleDateFormat(Defines.Options.DateOfBirthFormat).format(calendar.getTime()));
                }
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH));

            datePickerDialog.show();
        }
        else if (i == R.id.chat_sdk_select_country_button) {
            final CountryPicker picker = new CountryPicker();

            picker.setListener(new CountryPickerListener() {
                @Override
                public void onSelectCountry(String name, String code, String dialCode, int resId) {
                    NM.currentUser().setMetadataString(DaoDefines.Keys.Country, code);
                    loadCountryFlag(code);
                    picker.dismiss();
                }
            });

            picker.show(this.getSupportFragmentManager(), "");
        }
    }
}
