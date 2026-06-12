package com.example.smartweed;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class AnalysisViewModel extends ViewModel {
    public final MutableLiveData<Boolean> running    = new MutableLiveData<>(false);
    public final MutableLiveData<String>  outDir     = new MutableLiveData<>();
    public final MutableLiveData<String>  resultJson = new MutableLiveData<>();
    public final MutableLiveData<String>  error      = new MutableLiveData<>();
    public final MutableLiveData<Boolean> weedFilter = new MutableLiveData<>(false);
}
