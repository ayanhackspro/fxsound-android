# Shared FxSound DSP source and include lists.
#
# Both the app's NDK build (app/src/main/cpp/CMakeLists.txt) and the standalone
# native test build (app/src/test-native/CMakeLists.txt) include this file, so the
# test suite always exercises exactly the same sources that ship in the app.
#
# The including project must set FXSOUND_DSP_ROOT to the directory that contains
# the "fxsound_dsp" folder before including this file.

set(FXSOUND_DSP_INCLUDES
    ${FXSOUND_DSP_ROOT}/fxsound_dsp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/include
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/include
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/MTH
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/pstr
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Play/Play32
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Aural/Aural032
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Aural/Aural0
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Maximizer/Maxi32
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Lex/Lex32
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/wide/Wide32
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Dly32/Dly832
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Dly8
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Peq/Peq832
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Peq/Peq8
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptComSftDfx
    ${FXSOUND_DSP_ROOT}/engine
    ${FXSOUND_DSP_ROOT}/jni
)

# The DSP core itself. The JNI bridge is deliberately NOT in this list: it requires
# jni.h and is not part of the signal path, so the test binary does not need it.
set(FXSOUND_DSP_CORE_SOURCES
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/DfxDsp.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/DfxDspEq.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/DfxDspPreset.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/DfxDspPrivate.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptComSftDfx/Comsftwr.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptComSftDfx/ComsftwrCPP.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Aural/Aural032/Auralp32.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Aural/Aural0/Auralp.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Dly32/Dly832/dly8p32.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Dly8/Dly8p.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Lex/Lex32/Lex32.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Maximizer/Maxi32/Maxi32.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Peq/Peq832/peq8p32.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Peq/Peq8/peq8p.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/Play/Play32/Play32.c
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptechDsp/wide/Wide32/Wide32.c

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Com.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Comeprom.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Comget.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/ComMem.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Compass.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Comread.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Comwave.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/COM/Comwrite.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpComm.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpEq.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpGet.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpInit.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpProcess.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpProcessClear.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpProcessInt.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpProcessReal.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpQnt.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpQuit.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpRegistryStandard.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpSession.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpSet.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpSpectrum.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxp/dfxpUniversal.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/dfxSharedUtil/dfxSharedUtil.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/BinauralSync/BinauralSynGet.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/BinauralSync/BinauralSynInit.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/BinauralSync/BinauralSynProcess.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/BinauralSync/BinauralSynSet.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/GraphicEq/GraphicEqGet.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/GraphicEq/GraphicEqInit.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/GraphicEq/GraphicEqInitBands.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/GraphicEq/GraphicEqInitSections.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/GraphicEq/GraphicEqProcess.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/GraphicEq/GraphicEqSet.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/spectrum/spectrumGet.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/spectrum/spectrumInit.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/spectrum/spectrumMessageValues.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/spectrum/spectrumProcess.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/spectrum/spectrumReset.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/spectrum/spectrumSet.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/SurroundSyn/SurroundSynInit.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/DspUtil/SurroundSyn/SurroundSynProcess.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Filt/Fil12But.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Filt/FiltbiqdSos.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Filt/FiltCalcBiqd.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Filt/FiltCalcFilterResponse.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Filt/Filtpoly.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Filt/FiltRun.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/PRELST/Prelst.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/PWAV/PwavConvert.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/MTH/MthUtil.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/MTH/MthBuffer.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/MTH/Mthfreq.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/pstr/pstrWide.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qnt.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qnt2But.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/QntitoBoostCut.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qntitol.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qntitor.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qntitor2.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qntrtoi.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qntrtol.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/Qnt/Qntrtor.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/realSample/realSampleForceLegalValues.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/SOS/Sos.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/SOS/SosGet.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/SOS/SosProcess.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/SOS/SosSet.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/VALS/Vals.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/VALS/Valscfg.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/VALS/Valsfile.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/VALS/Valsget.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/VALS/Valsset.cpp
    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/VALS/Valswarp.cpp

    ${FXSOUND_DSP_ROOT}/fxsound_dsp/ptutil/android_dsp_compat.cpp
)
