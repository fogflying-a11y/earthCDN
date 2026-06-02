-keepattributes LineNumberTable

# AndroidX Recycler
-keep class androidx.recyclerview.widget.LinearLayoutManager { *; }

# data binding
-keep class ooo.oxo.apps.earth.databinding.** { *; }

# play services
-dontwarn com.google.android.gms.**
