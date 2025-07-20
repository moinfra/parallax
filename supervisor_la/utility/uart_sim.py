#!/usr/bin/env python3
# -*- encoding=utf-8 -*-

"""
UART Simulation Host for Verilator/STDIO-based environments.

This script acts as a bridge between the user's console and a simulated CPU
(e.g., a Verilator model) that communicates via its standard input and output.
It faithfully reproduces the behavior of an interactive serial terminal, allowing
developers to debug firmware (like kernel.bin) without needing physical hardware.

How it works:
1.  It launches the provided simulation executable as a subprocess.
2.  It redirects the subprocess's stdin and stdout to pipes that this script controls.
3.  A dedicated thread reads from the user's console (sys.stdin) without blocking
    the main loop. Characters are put into a thread-safe queue.
4.  The main loop continuously does two things:
    a. Uses `select` to check for and read any output from the simulation's stdout,
       printing it immediately to the user's console.
    b. Checks the queue for any user input and writes it to the simulation's stdin.

This ensures a responsive, bidirectional communication channel, just like a real
serial port connection.
"""

import sys
import os
import subprocess
import threading
import queue
import select
import argparse

def enqueue_stdin(q):
    """Target function for the reader thread.
    Reads from sys.stdin byte by byte and puts the result into a queue.
    """
    # Use sys.stdin.buffer.read for raw binary I/O
    for byte in iter(lambda: sys.stdin.buffer.read(1), b''):
        q.put(byte)

def main():
    """Main function to set up and run the simulation host."""
    parser = argparse.ArgumentParser(
        description='Run a Verilator simulation with interactive STDIO-based UART.',
        formatter_class=argparse.RawTextHelpFormatter
    )
    parser.add_argument(
        'sim_executable',
        help='Path to the Verilator simulation executable.'
    )
    parser.add_argument(
        'kernel_binary',
        help='Path to the kernel.bin file to be loaded by the simulation.'
    )
    parser.add_argument(
        '--args',
        nargs=argparse.REMAINDER,
        help='Additional arguments to pass to the simulation executable.'
    )

    args = parser.parse_args()

    # --- Verify paths ---
    if not os.path.exists(args.sim_executable):
        print(f"Error: Simulation executable not found at '{args.sim_executable}'", file=sys.stderr)
        sys.exit(1)
    if not os.path.exists(args.kernel_binary):
        print(f"Error: Kernel binary not found at '{args.kernel_binary}'", file=sys.stderr)
        sys.exit(1)

    # --- Launch the simulation subprocess ---
    command = [os.path.abspath(args.sim_executable), os.path.abspath(args.kernel_binary)]
    if args.args:
        command.extend(args.args)

    print(f"Starting simulation: {' '.join(command)}\n")
    try:
        sim_proc = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=sys.stderr, # Forward simulation errors directly to the console
            bufsize=0 # Use unbuffered I/O
        )
    except Exception as e:
        print(f"Error launching simulation: {e}", file=sys.stderr)
        sys.exit(1)

    # --- Set up the non-blocking input reader ---
    input_queue = queue.Queue()
    reader_thread = threading.Thread(target=enqueue_stdin, args=(input_queue,))
    reader_thread.daemon = True  # Thread will exit when main thread exits
    reader_thread.start()

    print("--- Interactive session started. Press Ctrl+C or close stdin to exit. ---\n")

    # --- Main communication loop ---
    try:
        while sim_proc.poll() is None:
            # Check for output from the simulation (non-blocking)
            readable, _, _ = select.select([sim_proc.stdout], [], [], 0.01)
            if readable:
                # Read one byte at a time to ensure interactivity
                data = sim_proc.stdout.read(1)
                if data:
                    sys.stdout.buffer.write(data)
                    sys.stdout.flush()
                else: # stdout pipe was closed
                    break

            # Check for input from the user (non-blocking)
            try:
                user_input = input_queue.get_nowait()
                sim_proc.stdin.write(user_input)
                sim_proc.stdin.flush()
            except queue.Empty:
                pass # No user input, continue

    except KeyboardInterrupt:
        print("\n--- Session terminated by user. ---")
    finally:
        if sim_proc.poll() is None:
            sim_proc.terminate()
            sim_proc.wait()
        print(f"Simulation exited with code: {sim_proc.returncode}")

if __name__ == "__main__":
    main()
