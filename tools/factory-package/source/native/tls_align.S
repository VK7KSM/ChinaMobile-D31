    .section .tdata.d31_align,"awT",%progbits
    .p2align 6
    .global d31_tls_alignment_anchor
    .type d31_tls_alignment_anchor,%object
d31_tls_alignment_anchor:
    .byte 0
    .size d31_tls_alignment_anchor, .-d31_tls_alignment_anchor
