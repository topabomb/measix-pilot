/* Linux x86_64 raw-fork probe. Build with:
 * gcc -nostdlib -static -Wl,--build-id=none -o fork-registers proot-fork-registers-x86_64.S
 * Both syscall return paths must preserve argument registers. No libc fallback is involved.
 */
.global _start
.section .text
_start:
    mov $0x111, %rdi
    mov $0x222, %rsi
    mov $0x333, %rdx
    mov $0x444, %r10
    mov $0x555, %r8
    mov $0x666, %r9
    mov $57, %rax
    syscall
    cmp $0x111, %rdi
    jne failed
    cmp $0x222, %rsi
    jne failed
    cmp $0x333, %rdx
    jne failed
    cmp $0x444, %r10
    jne failed
    cmp $0x555, %r8
    jne failed
    cmp $0x666, %r9
    jne failed
    test %rax, %rax
    js failed
    jz child
    mov %rax, %r12
    sub $8, %rsp
    movq $-1, (%rsp)
    mov %r12, %rdi
    mov %rsp, %rsi
    xor %rdx, %rdx
    xor %r10, %r10
    mov $61, %rax
    syscall
    cmp %r12, %rax
    jne failed
    cmpl $0, (%rsp)
    jne failed
    mov $1, %rax
    mov $1, %rdi
    lea success(%rip), %rsi
    mov $18, %rdx
    syscall
    cmp $18, %rax
    jne failed
    jmp done
child:
    mov $39, %rax
    syscall
    test %rax, %rax
    jle failed
done:
    xor %rdi, %rdi
    mov $60, %rax
    syscall
failed:
    mov $77, %rdi
    mov $60, %rax
    syscall
.section .rodata
success:
    .ascii "fork-registers-ok\n"
.section .note.GNU-stack,"",@progbits
