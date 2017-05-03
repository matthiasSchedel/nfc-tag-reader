package com.matthias_schedel.nfctagreader;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.icu.util.Calendar;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.AsyncTask;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * The type Main App activity.
 */
public class MainActivity extends AppCompatActivity {
    /**
     * The constant MIME_TEXT_PLAIN.
     */
    public static final String MIME_TEXT_PLAIN = "text/plain";
    /**
     * The constant TAG.
     */
    public static final String TAG = "NfcDemo";

    private TextView latestTagContent;
    private TextView selectedContent;
    private NfcAdapter mNfcAdapter;
    private SQLiteDatabase contentHistory;
    private Spinner spinner;
    private List<String> globalContentList;

    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        latestTagContent = (TextView) findViewById(R.id.latestTagContentTextView);
        selectedContent = (TextView) findViewById(R.id.selectedContent);
        spinner = (Spinner) findViewById(R.id.spinner);


        updateSpinner();

        mNfcAdapter = NfcAdapter.getDefaultAdapter(this);

        if (mNfcAdapter == null) {
            Toast.makeText(this,"NFC not supported by this device",Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (!mNfcAdapter.isEnabled()) {
            latestTagContent.setText(R.string.nfc_disabled);
        } else {
            latestTagContent.setText(R.string.nfc_enabled);
        }

        handleIntent(getIntent());
    }

    @Override
    protected void onPause() {
        stopForegroundDispatch(this, mNfcAdapter);
        super.onPause();
    }

    @Override
    protected  void onNewIntent(Intent intent) {
        //to call new intent instead of new activity when a new intent gets asociated (NFC tag wird erkannt)
        handleIntent(intent);
    }
    private void handleIntent(Intent intent) {
        //TODO: handle Intent
        String action = intent.getAction();
        if(NfcAdapter.ACTION_NDEF_DISCOVERED.equals(action)) {
            String type = intent.getType();
            if(MIME_TEXT_PLAIN.equals(type)) {
                Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
                new NdefReaderTask().execute(tag);
            } else {
                Log.d(TAG, "Wrong mime type:" + type);
            }
        } else if (NfcAdapter.ACTION_TECH_DISCOVERED.equals(action)) {
            //In case we use Tech discovery
            Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
            String[] techList = tag.getTechList();
            String searchedTech = Ndef.class.getName();

            for (String tech : techList) {
                if (searchedTech.equals(tech)) {
                    new NdefReaderTask().execute(tag);
                    break;
                }
            }
        }

    }

    /**
     * @param activity The corresponging {@link Activity} requesting the foreground dispatch
     * @param adapter The {@link NfcAdapter} used for Foreground fispatch
     */
    public static void setupForegroundDispatch(final Activity activity, final NfcAdapter adapter) {
        final Intent intent = new Intent(activity.getApplicationContext(), activity.getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

        final PendingIntent pendingIntent = PendingIntent.getActivity(activity.getApplicationContext(),0,intent,0);
        IntentFilter[] filters = new IntentFilter[1];
        String[][] techList = new String[][]{};

        // Notice that this is the same filter as in our manifest.
        filters[0] = new IntentFilter();
        filters[0].addAction(NfcAdapter.ACTION_NDEF_DISCOVERED);
        filters[0].addCategory(Intent.CATEGORY_DEFAULT);
        try {
            filters[0].addDataType(MIME_TEXT_PLAIN);
        } catch (IntentFilter.MalformedMimeTypeException e) {
            throw new RuntimeException("no plain text format -> Check your mime type.");
        }
        adapter.enableForegroundDispatch(activity, pendingIntent, filters, techList);
    }

    /**
     * @param activity The corresponding {@link BaseActivity} requesting to stop the foreground dispatch.
     * @param adapter The {@link NfcAdapter} used for the foreground dispatch.
     */
    @SuppressWarnings("JavadocReference")
    public static void stopForegroundDispatch(final Activity activity, NfcAdapter adapter) {
        adapter.disableForegroundDispatch(activity);
    }


    /**
     *
     */
    private class NdefReaderTask extends AsyncTask<Tag, Void, String> {
        @Override
        protected String doInBackground(Tag... params) {
            Tag tag = params[0];

            Ndef ndef = Ndef.get(tag);
            if (ndef == null) {
                //NDEF not supported by tag
                return null;
            }

            NdefMessage ndefMessage = ndef.getCachedNdefMessage();
            NdefRecord[] records = ndefMessage.getRecords();

            for (NdefRecord ndefRecord : records) {
                if (ndefRecord.getTnf() == NdefRecord.TNF_WELL_KNOWN
                        && Arrays.equals(ndefRecord.getType(), NdefRecord.RTD_TEXT)) {
                    try {
                        return readText(ndefRecord);
                    } catch (UnsupportedEncodingException e) {
                        Log.e(TAG, "Unsupported encoding ", e);
                    }
                }
            }
            return null;
        }

        /**
         *
         * @param record
         * @return String the content on the tag if successfull
         * @throws UnsupportedEncodingException
         */
        private String readText(NdefRecord record) throws UnsupportedEncodingException {
             /*
             * See NFC forum specification for "Text Record Type Definition" at 3.2.1
             *
             * http://www.nfc-forum.org/specs/
             *
             * bit_7 definiert encoding
             * bit_6 is reserved for future use, must be 0!!
             * bit_5..0 length of IANA language code (language + optional region code zb. en-GB)
             */
            byte[] payload = record.getPayload();

            //get text encoding
            String textEncoding = ((payload[0] & 128) == 0) ? "UTF-8" : "UTF-16";

            //get language code
            int languageCodeLength = payload[0] & 0063;
            return new String(payload, languageCodeLength + 1, payload.length - languageCodeLength - 1, textEncoding);
        }

        @RequiresApi(api = Build.VERSION_CODES.N)
        @Override
        protected void onPostExecute(String result) {
            if (result != null) {
                latestTagContent.setText(result);
                MainActivity.this.insertRecordInDatabase(result);
                MainActivity.this.updateSpinner();
            } else {
                latestTagContent.setText("Error: tag empty!");
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void insertRecordInDatabase(String record) {
        long time = Calendar.getInstance().getTimeInMillis();
        try {
            contentHistory = this.openOrCreateDatabase("Nfc_History", MODE_PRIVATE, null);
            contentHistory.execSQL("CREATE TABLE IF NOT EXISTS Nfc_History (content VARCHAR, date INT(10),id INTEGER PRIMARY KEY)");
            contentHistory.execSQL("INSERT INTO Nfc_History (content, date) VALUES ('" + record + "', "+String.valueOf(time)+")");
            MainActivity.this.updateSpinner();
        } catch (Exception e) {
            Log.e("Error on insertRecord", record);
            e.printStackTrace();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void updateSpinner() {
        List<String> contentList = new LinkedList<String>();
        List<String> dateList = new LinkedList<String>();

        try {
            contentHistory = this.openOrCreateDatabase("Nfc_History", MODE_PRIVATE, null);
            contentHistory.execSQL("CREATE TABLE IF NOT EXISTS Nfc_History (content VARCHAR, date INT(10),id INTEGER PRIMARY KEY)");

            Cursor curs = contentHistory.rawQuery("SELECT * FROM Nfc_History", null);
            int contentIndex = curs.getColumnIndex("content");
            int dateIndex = curs.getColumnIndex("date");

            curs.moveToFirst();
            while (curs != null) {
                String content = curs.getString(contentIndex);
                long date = curs.getLong(dateIndex);
                contentList.add(content);
                dateList.add(getDate(date, "dd/MM/yyyy hh:mm:ss.SSS"));
                curs.moveToNext();
            }
        } catch (Exception e) {
            Log.e("Error on updateSpinner", dateList.toString());
            e.printStackTrace();
        }

        globalContentList = contentList;
        // Creating adapter for spinner
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, dateList);

        // Drop down layout style - list view with radio button
        dataAdapter
                .setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // attaching data adapter to spinner
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedContent.setText(globalContentList.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                selectedContent.setText("no pos selected");

            }
        });

        spinner.setAdapter(dataAdapter);

    }

            /**
             * Return date in specified format.
             * @param milliSeconds Date in milliseconds
             * @param dateFormat Date format
             * @return String representing date in specified format
             */
            @RequiresApi(api = Build.VERSION_CODES.N)
            public static String getDate ( long milliSeconds, String dateFormat)
            {
                // Create a DateFormatter object for displaying date in specified format.
                SimpleDateFormat formatter = new SimpleDateFormat(dateFormat);
                // Create a calendar object that will convert the date and time value in milliseconds to date.
                Calendar calendar = Calendar.getInstance();
                calendar.setTimeInMillis(milliSeconds);
                return formatter.format(calendar.getTime());
            }

            private void clearList() {
                try {
                    contentHistory = this.openOrCreateDatabase("Nfc_History", MODE_PRIVATE, null);
                    contentHistory.execSQL("CREATE TABLE IF NOT EXISTS Nfc_History (content VARCHAR, date INT(10),id INTEGER PRIMARY KEY)");
                    contentHistory.execSQL("DROP TABLE Nfc_History");
                    globalContentList = new LinkedList<String>();
            }  catch (Exception e) {
                    e.printStackTrace();
            }
            }
        }