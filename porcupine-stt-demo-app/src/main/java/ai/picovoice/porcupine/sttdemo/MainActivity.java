/*
    Copyright 2021 Picovoice Inc.

    You may not use this file except in compliance with the license. A copy of the license is
    located in the "LICENSE" file accompanying this source.

    Unless required by applicable law or agreed to in writing, software distributed under the
    License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
    express or implied. See the License for the specific language governing permissions and
    limitations under the License.
*/

package ai.picovoice.porcupine.sttdemo;


import static android.content.ContentValues.TAG;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.jetbrains.annotations.NotNull;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

import ai.picovoice.porcupine.Porcupine;
import ai.picovoice.porcupine.PorcupineActivationException;
import ai.picovoice.porcupine.PorcupineActivationLimitException;
import ai.picovoice.porcupine.PorcupineActivationRefusedException;
import ai.picovoice.porcupine.PorcupineActivationThrottledException;
import ai.picovoice.porcupine.PorcupineException;
import ai.picovoice.porcupine.PorcupineInvalidArgumentException;
import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineManagerCallback;

import ai.picovoice.porcupine.sttdemo.SslHandler;

enum AppState {
    STOPPED,
    WAKEWORD,
    STT
}

public class MainActivity extends AppCompatActivity {
    private PorcupineManager porcupineManager = null;

    private static final String ACCESS_KEY = "Ux8Gh4lpNAhqtKfKMG+GN6Fj9GtJhyz2g+DxfAkn3C7HxvcOATo8Iw==";
    private static final String ENDPOINT_URL = "http://mini.qssdemo.com/mini-en";


    private final Porcupine.BuiltInKeyword defaultKeyword = Porcupine.BuiltInKeyword.ALEXA;

    private TextView intentTextView;
    private ToggleButton recordButton;

    private SpeechRecognizer speechRecognizer;
    private Intent speechRecognizerIntent;

    private AppState currentState;

    private OkHttpClient httpClient;

    private void displayError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private final PorcupineManagerCallback porcupineManagerCallback = new PorcupineManagerCallback() {
        @Override
        public void invoke(int keywordIndex) {
            runOnUiThread(() -> {
                intentTextView.setText("");
                try {
                    // need to stop porcupine manager before speechRecognizer can start listening.
                    porcupineManager.stop();
                } catch (PorcupineException e) {
                    displayError("Failed to stop Porcupine.");
                    return;
                }

                speechRecognizer.startListening(speechRecognizerIntent);
                currentState = AppState.STT;
            });
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        intentTextView = findViewById(R.id.intentView);
        recordButton = findViewById(R.id.record_button);

        // on android 11, RecognitionService has to be specifically added to android manifest.
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            displayError("Speech Recognition not available.");
        }
//        SslHandler.handleSSLHandshake();
        httpClient = new OkHttpClient();
        speechRecognizerIntent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        speechRecognizerIntent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        speechRecognizerIntent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());

        try {
            porcupineManager = new PorcupineManager.Builder()
                    .setAccessKey(ACCESS_KEY)
                    .setKeywordPath(copyRawResourceToFile(R.raw.hey_mini))
                    .setSensitivity(0.7f)
                    .build(getApplicationContext(), porcupineManagerCallback);

        } catch (PorcupineInvalidArgumentException e) {
            onPorcupineInitError(e.getMessage());
        } catch (PorcupineActivationException e) {
            onPorcupineInitError("AccessKey activation error");
        } catch (PorcupineActivationLimitException e) {
            onPorcupineInitError("AccessKey reached its device limit");
        } catch (PorcupineActivationRefusedException e) {
            onPorcupineInitError("AccessKey refused");
        } catch (PorcupineActivationThrottledException e) {
            onPorcupineInitError("AccessKey has been throttled");
        } catch (PorcupineException e) {
            onPorcupineInitError("Failed to initialize Porcupine " + e.getMessage());
        }

        currentState = AppState.STOPPED;

        if (hasRecordPermission()) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new SpeechListener());
            playback(0);
        } else {
            requestRecordPermission();
        }

        Intent intent = getIntent();
        if (intent.hasExtra("close")){
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new SpeechListener());
            speechRecognizer.startListening(speechRecognizerIntent);
            currentState = AppState.STT;
            final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    System.exit(0);
                    finish();
                }
            }, 1000);
        }
    }

    private void onPorcupineInitError(final String errorMessage) {
        runOnUiThread(() -> {
            TextView errorText = findViewById(R.id.errorMessage);
            errorText.setText(errorMessage);
            errorText.setVisibility(View.VISIBLE);

            ToggleButton recordButton = findViewById(R.id.record_button);
            recordButton.setBackground(ContextCompat.getDrawable(
                    getApplicationContext(),
                    R.drawable.disabled_button_background));
            recordButton.setChecked(false);
            recordButton.setEnabled(false);
        });
    }

    @Override
    protected void onStop() {
        if (recordButton.isChecked()) {
            stopService();
            recordButton.toggle();
            speechRecognizer.destroy();
        }

        super.onStop();
    }

    private boolean hasRecordPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == 
            PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, 0);
    }

    private void playback(int milliSeconds) {
        speechRecognizer.stopListening();
        currentState = AppState.WAKEWORD;

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentState == AppState.WAKEWORD) {
                    try {
                        porcupineManager.start();
                    } catch (PorcupineException e) {
                        displayError("Failed to start porcupine.");
                    }
                    intentTextView.setTextColor(Color.WHITE);
                    intentTextView.setText("Listening for hey Mini ...");
                }
            }
        }, milliSeconds);
    }

    private void stopService() {
        if (porcupineManager != null) {
            try {
                porcupineManager.stop();
            } catch (PorcupineException e) {
                displayError("Failed to stop porcupine.");
            }
        }

        intentTextView.setText("");
        speechRecognizer.stopListening();
        speechRecognizer.destroy();
        currentState = AppState.STOPPED;
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[]
            permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0 || grantResults[0] == PackageManager.PERMISSION_DENIED) {
            onPorcupineInitError("Microphone permission is required for this demo");
        } else {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new SpeechListener());
            playback(0);
        }
    }

    public void process(View view) {
        if (recordButton.isChecked()) {
            if (hasRecordPermission()) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
                speechRecognizer.setRecognitionListener(new SpeechListener());
                playback(0);
            } else {
                requestRecordPermission();
            }
        } else {
            stopService();
        }
    }

    private class SpeechListener implements RecognitionListener {
        @Override
        public void onReadyForSpeech(Bundle params) {
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            switch (error) {
                case SpeechRecognizer.ERROR_AUDIO:
                    displayError("Error recording audio.");

                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    displayError("Insufficient permissions.");

                    break;
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_NETWORK:
                    displayError("Network Error.");

                    break;
                case SpeechRecognizer.ERROR_NO_MATCH:
                    if (recordButton.isChecked()) {
                        displayError("No recognition result matched.");
                    }
                    return;
                case SpeechRecognizer.ERROR_CLIENT:
                    return;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    displayError("Recognition service is busy.");
                    playback(500);
                    break;
                case SpeechRecognizer.ERROR_SERVER:
                    displayError("Server Error.");
                    break;
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    displayError("No speech input.");
                    playback(500);
                    break;
                default:
                    displayError("Something wrong occurred.");
            }
//
//            stopService();
//            recordButton.toggle();
        }

        @Override
        public void onResults(Bundle results) {
            ArrayList<String> data = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            intentTextView.setTextColor(Color.WHITE);
            intentTextView.setText(data.get(0));

            playback(1000);
            sendTranscriptToEndpointAsync(data.get(0));
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            ArrayList<String> data = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            intentTextView.setTextColor(Color.DKGRAY);
            intentTextView.setText(data.get(0));
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    }
    private String copyRawResourceToFile(int resourceId) {
        try (InputStream inputStream = getResources().openRawResource(resourceId);
             ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = inputStream.read(buffer)) > 0) {
                outputStream.write(buffer, 0, length);
            }
            File outFile = new File(getFilesDir(), "hey_mini.ppn");
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                fos.write(outputStream.toByteArray());
            }
            return outFile.getAbsolutePath();
        } catch (IOException e) {
            Log.e(TAG, "Error copying resource to file", e);
            return null;
        }
    }
    private void sendTranscriptToEndpointAsync(String transcript) {

        JSONObject json = new JSONObject();
        try {
            json.put("command", transcript);
            json.put("language","en");
        } catch (JSONException e) {
            Log.e(TAG, "JSON exception", e);
            return;
        }

        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), json.toString());
        Request request = new Request.Builder().url(ENDPOINT_URL).post(body).build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NotNull Call call, @NotNull IOException e) {
                Log.e(TAG, "Network error", e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(MainActivity.this, "Network Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onResponse(@NotNull Call call, @NotNull Response response) throws IOException {
                try {
                    String responseData = response.body().string();
                    Log.e("onResponse", responseData);
                }finally {
                    response.close();
                }
            }
        });
    }

}
