package com.example.signotext;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;

import android.os.Handler;
import android.os.Looper;

import com.example.signotext.ml.ModelUnquant;
import com.example.signotext.ml.SenasClassifier;

public class SeniaATexto extends AppCompatActivity {
    private static final int REQUEST_CAMERA_PERMISSION = 200;
    private static final int DETECTION_INTERVAL_MS = 3000;
    private static final float MIN_CONFIDENCE = 0.85f;

    private String lastDetectedLetter = "";

    private TextureView textureView;
    private CameraManager cameraManager;
    private String cameraId;
    private boolean isCameraFront = false;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    private ModelUnquant model; // Usa el nuevo modelo
    private final List<String> labels = Arrays.asList(
            "A", "B", "C", "D", "E", "F", "G", "H", "I", "J",
            "K", "L", "M", "N", "O", "P", "Q", "R", "S", "T",
            "U", "V", "W", "X", "Y", "Z"
    );

    private Handler handler;
    private Runnable detectionRunnable;
    private StringBuilder currentText = new StringBuilder();
    private long lastDetectionTime = 0;
    private StringBuilder translatedText = new StringBuilder(); // Texto traducido estático


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_senia_a_texto);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);

            ImageView imageViewFlecha = findViewById(R.id.flecha_retroceder);
            imageViewFlecha.setOnClickListener(v1 -> finish());

            return insets;
        });

        try {
            model = ModelUnquant.newInstance(this); // Carga el nuevo modelo
        } catch (IOException e) {
            e.printStackTrace();
        }

        textureView = findViewById(R.id.camera_preview);
        textureView.setSurfaceTextureListener(surfaceTextureListener);

        Button switchCameraButton = findViewById(R.id.button_switch_camera);
        switchCameraButton.setOnClickListener(v -> switchCamera());

        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
            return;
        }
        startCamera();

        handler = new Handler(Looper.getMainLooper());
        detectionRunnable = new Runnable() {
            @Override
            public void run() {
                if (System.currentTimeMillis() - lastDetectionTime >= DETECTION_INTERVAL_MS) {
                    lastDetectionTime = System.currentTimeMillis();
                }
                handler.postDelayed(this, DETECTION_INTERVAL_MS);
            }
        };
        handler.post(detectionRunnable);
    }

    private void startCamera() {
        try {
            cameraId = getCameraId();
            if (cameraId != null) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                cameraManager.openCamera(cameraId, stateCallback, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private String getCameraId() throws CameraAccessException {
        for (String cameraId : cameraManager.getCameraIdList()) {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == (isCameraFront ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                return cameraId;
            }
        }
        return null;
    }

    private void switchCamera() {
        isCameraFront = !isCameraFront;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (cameraDevice != null) {
                cameraDevice.close();
            }
            startCamera();
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
            startCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {
            Bitmap bitmap = textureView.getBitmap();
            onImageAvailable(bitmap);
        }
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            if (texture == null) {
                return;
            }
            texture.setDefaultBufferSize(textureView.getWidth(), textureView.getHeight());
            Surface surface = new Surface(texture);

            final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    if (cameraDevice == null) {
                        return;
                    }
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                }
            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void onImageAvailable(Bitmap bitmap) {
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, 224, 224, true); // Ajusta el tamaño según el modelo
        ByteBuffer byteBuffer = convertBitmapToByteBuffer(resizedBitmap);
        TensorBuffer inputBuffer = TensorBuffer.createFixedSize(new int[]{1, 224, 224, 3}, DataType.FLOAT32);
        inputBuffer.loadBuffer(byteBuffer);

        try {
            ModelUnquant.Outputs outputs = model.process(inputBuffer);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();
            float[] probabilities = outputFeature0.getFloatArray();
            int maxIndex = getMaxIndex(probabilities);
            float maxProbability = probabilities[maxIndex];
            String predictedLabel = labels.get(maxIndex);

            Log.d("SeniaATexto", "Detected: " + predictedLabel + " with confidence: " + maxProbability);

            if (maxProbability >= MIN_CONFIDENCE) {
                if (!predictedLabel.equals(lastDetectedLetter)) {
                    appendLetter(predictedLabel);
                    lastDetectedLetter = predictedLabel;

                    // Llama a finalizeCharacterTranslation después de un intervalo
                    handler.postDelayed(() -> finalizeCharacterTranslation(), DETECTION_INTERVAL_MS);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * 1 * 224 * 224 * 3); // Ajusta el tamaño según el modelo
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[224 * 224]; // Ajusta el tamaño según el modelo
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixelIndex = 0;
        for (int i = 0; i < 224; ++i) { // Ajusta el tamaño según el modelo
            for (int j = 0; j < 224; ++j) { // Ajusta el tamaño según el modelo
                final int val = intValues[pixelIndex++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) * (1.f / 255.f));
                byteBuffer.putFloat(((val >> 8) & 0xFF) * (1.f / 255.f));
                byteBuffer.putFloat((val & 0xFF) * (1.f / 255.f));
            }
        }
        return byteBuffer;
    }

    private int getMaxIndex(float[] probabilities) {
        int maxIndex = 0;
        float maxProbability = probabilities[0];
        for (int i = 1; i < probabilities.length; i++) {
            if (probabilities[i] > maxProbability) {
                maxProbability = probabilities[i];
                maxIndex = i;
            }
        }
        return maxIndex;
    }

    private void appendLetter(String letter) {
        Log.d("SeniaATexto", "Appending letter: " + letter);
        // Agrega el carácter al texto actual
        currentText.append(letter);
        updateTranslationText();
    }


    private void updateTranslationText() {
        runOnUiThread(() -> {
            TextView translationText = findViewById(R.id.translation_text);
            if (translationText != null) {
                // Combina el texto traducido con el texto actual en proceso
                String displayText = translatedText.toString() + currentText.toString();
                translationText.setText(displayText);
            } else {
                Log.e("SeniaATexto", "TextView not found!");
            }
        });
    }

    private void finalizeCharacterTranslation() {
        // Mueve el texto actual al texto traducido y limpia el texto actual
        translatedText.append(currentText.toString());
        currentText.setLength(0); // Limpia el texto actual
        updateTranslationText();
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                finish();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (model != null) {
            model.close();
        }
        handler.removeCallbacks(detectionRunnable);
    }
}