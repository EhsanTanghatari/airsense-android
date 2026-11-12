# osmdroid
-dontwarn org.osmdroid.**
-keep class org.osmdroid.** { *; }

# Bluetooth fallback uses reflection on BluetoothDevice.createRfcommSocket
-keepclassmembers class android.bluetooth.BluetoothDevice { *; }
