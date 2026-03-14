package com.example.walkassist;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public class ModelInspector {
    @Test
    public void modelAssetsExist() {
        assertTrue(new File("src/main/assets/yolov8n.tflite").exists());
        assertTrue(new File("src/main/assets/midas_v21_small.tflite").exists());
        assertTrue(new File("src/main/assets/labels.txt").exists());
    }
}
