# Generate the bit-exact vectors shared by the OPERA DSP bare-metal tests.
get_filename_component(OPERA_SOC_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
set(OPERA_DSP_CHAIN_GOLDEN_HEADER
    "${CMAKE_BINARY_DIR}/generated/opera_dsp_chain_golden.h")
set(OPERA_DSP_GOLDEN_OPTIONS
    "--num-points 256 --frames 3 --amplitude-raw 64 --cfar-scale-raw 49152 --cfar-ref 16 --cfar-guard 4 --cfar-shift 4 --cfar-mode 0 --cfar-edge-policy 1 --peak-grouping 0 --tone-amplitude-raw 512 --tone-bins 37,123,211")

add_custom_command(
    OUTPUT "${OPERA_DSP_CHAIN_GOLDEN_HEADER}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${CMAKE_BINARY_DIR}/generated"
    COMMAND sbt "chipyard/Test/runMain chipyard.example.operadsp.DspChainApp ${OPERA_DSP_GOLDEN_OPTIONS} --out ${OPERA_DSP_CHAIN_GOLDEN_HEADER}"
    WORKING_DIRECTORY "${OPERA_SOC_ROOT}"
    DEPENDS
        "${OPERA_SOC_ROOT}/generators/chipyard/src/test/scala/example/opera-dsp/DspChainApp.scala"
        "${OPERA_SOC_ROOT}/generators/chipyard/src/test/scala/example/opera-dsp/DspChainModel.scala"
        "${OPERA_SOC_ROOT}/generators/chipyard/src/test/scala/example/opera-dsp/DspChainTestUtils.scala"
        "${OPERA_SOC_ROOT}/generators/chipyard/src/main/scala/example/opera-dsp/OperaDspChain.scala"
    COMMENT "Generating OPERA DSP chain golden vectors"
)
