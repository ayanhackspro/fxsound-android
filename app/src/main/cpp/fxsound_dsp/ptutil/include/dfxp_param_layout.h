/*
 * dfxp_param_layout.h
 *
 * Compile-time guard for the FxSound DSP parameter protocol.
 *
 * The upstream DSP was written for a platform where `long` is 32 bits (Win32). The
 * parameter block is an array of 32-bit words (`dsp_params` in the comSftwr handle),
 * and both sides of the protocol address it by word index:
 *
 *   - The PC side writes it:  comSftwrWriteParam() stores at dsp_params[offset].
 *   - The DSP side reads it:  the module kernels index COMM_MEM_OFFSET[<index>],
 *     where COMM_MEM_OFFSET is a float* into the same array.
 *   - The DSP side also overlays it with a struct (dspPlayStructType and friends),
 *     whose field order is defined to match those word indices.
 *
 * On Android, `long` is 64 bits (LP64). A struct overlay built with `long` fields is
 * therefore twice the size it was on Win32, every field after the first lands at the
 * wrong word, and the DSP silently reads the wrong parameters. That is what made the
 * application's effect sliders have no effect on the output.
 *
 * The parameter structs now use fixed-width 32-bit types, and the assertions below
 * lock that in. If a future change reintroduces a `long` (or otherwise changes the
 * layout) the build fails here rather than the DSP quietly misbehaving.
 *
 * The word indices come from boardrv1.h, which is the upstream's own definition of the
 * protocol.
 *
 * Upstream DSP code: https://github.com/fxsound2/fxsound-app
 * License: AGPL-3.0 (same as upstream)
 */

#ifndef _DFXP_PARAM_LAYOUT_H_
#define _DFXP_PARAM_LAYOUT_H_

#include <stddef.h>

#include "codedefs.h"
#include "c_aural.h"
#include "c_lex.h"
#include "c_max.h"
#include "c_play.h"
#include "c_wid.h"

/*
 * Asserts that a field of a parameter overlay struct sits at the given 32-bit word
 * index of the parameter array.
 */
#define DFXP_ASSERT_WORD(struct_type, field, word_index)                              \
    static_assert(offsetof(struct_type, field) ==                                     \
                      static_cast<size_t>(word_index) * sizeof(float),                 \
                  #struct_type "::" #field " must sit at DSP parameter word "         \
                  #word_index " - the parameter protocol uses 32-bit words")

/* The common block, shared by every DSP module. boardrv1.h:142-157. */
#define DFXP_ASSERT_COMMON_BLOCK(struct_type)                                         \
    DFXP_ASSERT_WORD(struct_type, pc_to_dsp_flags, 0);                                 \
    DFXP_ASSERT_WORD(struct_type, dsp_to_pc_flags, 1);                                 \
    DFXP_ASSERT_WORD(struct_type, dsp_number_of_elements, 2);                          \
    DFXP_ASSERT_WORD(struct_type, dsp_sampling_freq, 3);                               \
    DFXP_ASSERT_WORD(struct_type, stereo_in_flag, 4);                                  \
    DFXP_ASSERT_WORD(struct_type, dsp_mute_in_flag, 5);                                \
    DFXP_ASSERT_WORD(struct_type, dry_gain, 10);                                      \
    DFXP_ASSERT_WORD(struct_type, wet_gain, 11);                                      \
    DFXP_ASSERT_WORD(struct_type, master_gain, 12);                                   \
    DFXP_ASSERT_WORD(struct_type, dsp_dma_in_transfer, 13)

/* The Aural Activator parameters, which the Play module shares memory with. */
DFXP_ASSERT_COMMON_BLOCK(dspAuralStructType);
DFXP_ASSERT_WORD(dspAuralStructType, aural_drive, 20);
DFXP_ASSERT_WORD(dspAuralStructType, aural_odd, 21);
DFXP_ASSERT_WORD(dspAuralStructType, aural_even, 22);
DFXP_ASSERT_WORD(dspAuralStructType, gain, 23);
DFXP_ASSERT_WORD(dspAuralStructType, a1, 24);
DFXP_ASSERT_WORD(dspAuralStructType, a0, 25);

/* The Play module's own parameters, which sit above the Activator's. */
DFXP_ASSERT_COMMON_BLOCK(dspPlayStructType);
DFXP_ASSERT_WORD(dspPlayStructType, bypass_on, 36);
DFXP_ASSERT_WORD(dspPlayStructType, activator_on, 37);
DFXP_ASSERT_WORD(dspPlayStructType, ambience_on, 38);
DFXP_ASSERT_WORD(dspPlayStructType, widener_on, 39);
DFXP_ASSERT_WORD(dspPlayStructType, bassboost_on, 40);
DFXP_ASSERT_WORD(dspPlayStructType, headphone_on, 41);
DFXP_ASSERT_WORD(dspPlayStructType, b0, 43);
DFXP_ASSERT_WORD(dspPlayStructType, b1, 44);
DFXP_ASSERT_WORD(dspPlayStructType, b2, 45);
DFXP_ASSERT_WORD(dspPlayStructType, a1, 46);
DFXP_ASSERT_WORD(dspPlayStructType, a2, 47);

/* The remaining DSP modules share the same common block. */
DFXP_ASSERT_COMMON_BLOCK(dspMaxiStructType);
DFXP_ASSERT_COMMON_BLOCK(dspLexStructType);
DFXP_ASSERT_COMMON_BLOCK(dspWideStructType);

#endif /* _DFXP_PARAM_LAYOUT_H_ */
