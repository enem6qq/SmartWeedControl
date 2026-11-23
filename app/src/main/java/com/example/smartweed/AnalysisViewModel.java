package com.example.smartweed;

import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

public class AnalysisViewModel extends ViewModel {
    public final MutableLiveData<Boolean> running    = new MutableLiveData<>(false);
    public final MutableLiveData<String>  outDir     = new MutableLiveData<>();
    public final MutableLiveData<String>  resultJson = new MutableLiveData<>();
    public final MutableLiveData<String>  error      = new MutableLiveData<>();
    public final MutableLiveData<Boolean> weedFilter = new MutableLiveData<>(false);

    /** Phase der laufenden Analyse (drittes Element von {@link #progress}) */
    public static final int PHASE_FRAME_SEARCH = 0;
    public static final int PHASE_ANALYSIS = 1;

    /** Fortschritt der laufenden Analyse:
     *  {aktuelles Bild (1-basiert), Gesamtanzahl, Phase (PHASE_*)} */
    public final MutableLiveData<int[]>   progress   = new MutableLiveData<>();
}
