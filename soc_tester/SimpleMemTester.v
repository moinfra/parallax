// Generator : SpinalHDL dev    git head : 3105a33b457518a7afeed8b0527b4d8b9dab2383
// Component : SimpleMemTester
// Git hash  : d6b0fe85ff2e31be91e518f2a6b530f197606af3

`timescale 1ns/1ps

module SimpleMemTester (
  output wire [7:0]    io_dpy0,
  output wire [7:0]    io_dpy1,
  output wire [15:0]   io_leds,
  input  wire          io_switch_btn,
  input  wire [31:0]   io_isram_dout,
  output wire [19:0]   io_isram_addr,
  output wire [31:0]   io_isram_din,
  output wire          io_isram_en,
  output wire          io_isram_re,
  output wire          io_isram_we,
  output wire [3:0]    io_isram_wmask,
  input  wire [31:0]   io_dsram_dout,
  output reg  [19:0]   io_dsram_addr,
  output reg  [31:0]   io_dsram_din,
  output reg           io_dsram_en,
  output reg           io_dsram_re,
  output reg           io_dsram_we,
  output reg  [3:0]    io_dsram_wmask,
  input  wire          io_uart_ar_ready,
  input  wire [7:0]    io_uart_r_bits_id,
  input  wire [1:0]    io_uart_r_bits_resp,
  input  wire [31:0]   io_uart_r_bits_data,
  input  wire          io_uart_r_bits_last,
  input  wire          io_uart_r_valid,
  input  wire          io_uart_aw_ready,
  input  wire          io_uart_w_ready,
  input  wire [7:0]    io_uart_b_bits_id,
  input  wire [1:0]    io_uart_b_bits_resp,
  input  wire          io_uart_b_valid,
  output wire [7:0]    io_uart_ar_bits_id,
  output wire [31:0]   io_uart_ar_bits_addr,
  output wire [7:0]    io_uart_ar_bits_len,
  output wire [2:0]    io_uart_ar_bits_size,
  output wire [1:0]    io_uart_ar_bits_burst,
  output wire          io_uart_ar_valid,
  output wire          io_uart_r_ready,
  output wire [7:0]    io_uart_aw_bits_id,
  output wire [31:0]   io_uart_aw_bits_addr,
  output wire [7:0]    io_uart_aw_bits_len,
  output wire [2:0]    io_uart_aw_bits_size,
  output wire [1:0]    io_uart_aw_bits_burst,
  output wire          io_uart_aw_valid,
  output wire [31:0]   io_uart_w_bits_data,
  output wire [3:0]    io_uart_w_bits_strb,
  output wire          io_uart_w_bits_last,
  output wire          io_uart_w_valid,
  output wire          io_uart_b_ready,
  input  wire          clk,
  input  wire          reset
);
  localparam TestState_S_WRITE = 2'd0;
  localparam TestState_S_WAIT_AFTER_WRITE = 2'd1;
  localparam TestState_S_READ = 2'd2;
  localparam TestState_S_WAIT_AFTER_READ = 2'd3;

  wire       [24:0]   _zz_timer_valueNext;
  wire       [0:0]    _zz_timer_valueNext_1;
  wire       [31:0]   _zz_writeData;
  reg        [1:0]    state;
  reg                 timer_willIncrement;
  reg                 timer_willClear;
  reg        [24:0]   timer_valueNext;
  reg        [24:0]   timer_value;
  wire                timer_willOverflowIfInc;
  wire                timer_willOverflow;
  reg        [19:0]   writeAddr;
  reg        [31:0]   writeData;
  reg        [15:0]   ledDisplay;
  `ifndef SYNTHESIS
  reg [143:0] state_string;
  `endif

  function  zz_timer_willIncrement(input dummy);
    begin
      zz_timer_willIncrement = 1'b0;
      zz_timer_willIncrement = 1'b1;
    end
  endfunction
  wire  _zz_1;

  assign _zz_timer_valueNext_1 = timer_willIncrement;
  assign _zz_timer_valueNext = {24'd0, _zz_timer_valueNext_1};
  assign _zz_writeData = (writeData + 32'h00000001);
  `ifndef SYNTHESIS
  always @(*) begin
    case(state)
      TestState_S_WRITE : state_string = "S_WRITE           ";
      TestState_S_WAIT_AFTER_WRITE : state_string = "S_WAIT_AFTER_WRITE";
      TestState_S_READ : state_string = "S_READ            ";
      TestState_S_WAIT_AFTER_READ : state_string = "S_WAIT_AFTER_READ ";
      default : state_string = "??????????????????";
    endcase
  end
  `endif

  assign _zz_1 = zz_timer_willIncrement(1'b0);
  always @(*) timer_willIncrement = _zz_1;
  always @(*) begin
    timer_willClear = 1'b0;
    case(state)
      TestState_S_WRITE : begin
        timer_willClear = 1'b1;
      end
      TestState_S_WAIT_AFTER_WRITE : begin
        if(timer_willOverflow) begin
          timer_willClear = 1'b1;
        end
      end
      TestState_S_READ : begin
        timer_willClear = 1'b1;
      end
      default : begin
        if(timer_willOverflow) begin
          timer_willClear = 1'b1;
        end
      end
    endcase
  end

  assign timer_willOverflowIfInc = (timer_value == 25'h17d7840);
  assign timer_willOverflow = (timer_willOverflowIfInc && timer_willIncrement);
  always @(*) begin
    if(timer_willOverflow) begin
      timer_valueNext = 25'h0;
    end else begin
      timer_valueNext = (timer_value + _zz_timer_valueNext);
    end
    if(timer_willClear) begin
      timer_valueNext = 25'h0;
    end
  end

  assign io_leds = ledDisplay;
  always @(*) begin
    io_dsram_en = 1'b0;
    case(state)
      TestState_S_WRITE : begin
        io_dsram_en = 1'b1;
      end
      TestState_S_WAIT_AFTER_WRITE : begin
      end
      TestState_S_READ : begin
        io_dsram_en = 1'b1;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_dsram_we = 1'b0;
    case(state)
      TestState_S_WRITE : begin
        io_dsram_we = 1'b1;
      end
      TestState_S_WAIT_AFTER_WRITE : begin
      end
      TestState_S_READ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_dsram_re = 1'b0;
    case(state)
      TestState_S_WRITE : begin
      end
      TestState_S_WAIT_AFTER_WRITE : begin
      end
      TestState_S_READ : begin
        io_dsram_re = 1'b1;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_dsram_addr = 20'h0;
    case(state)
      TestState_S_WRITE : begin
        io_dsram_addr = writeAddr;
      end
      TestState_S_WAIT_AFTER_WRITE : begin
      end
      TestState_S_READ : begin
        io_dsram_addr = writeAddr;
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_dsram_din = 32'h0;
    case(state)
      TestState_S_WRITE : begin
        io_dsram_din = writeData;
      end
      TestState_S_WAIT_AFTER_WRITE : begin
      end
      TestState_S_READ : begin
      end
      default : begin
      end
    endcase
  end

  always @(*) begin
    io_dsram_wmask = 4'b0000;
    case(state)
      TestState_S_WRITE : begin
        io_dsram_wmask = 4'b1111;
      end
      TestState_S_WAIT_AFTER_WRITE : begin
      end
      TestState_S_READ : begin
      end
      default : begin
      end
    endcase
  end

  assign io_isram_en = 1'b0;
  assign io_isram_we = 1'b0;
  assign io_isram_re = 1'b0;
  assign io_isram_addr = 20'h0;
  assign io_isram_din = 32'h0;
  assign io_isram_wmask = 4'b0000;
  assign io_uart_ar_valid = 1'b0;
  assign io_uart_aw_valid = 1'b0;
  assign io_uart_w_valid = 1'b0;
  assign io_uart_r_ready = 1'b0;
  assign io_uart_b_ready = 1'b0;
  assign io_uart_ar_bits_id = 8'h0;
  assign io_uart_ar_bits_addr = 32'h0;
  assign io_uart_ar_bits_len = 8'h0;
  assign io_uart_ar_bits_size = 3'b000;
  assign io_uart_ar_bits_burst = 2'b00;
  assign io_uart_aw_bits_id = 8'h0;
  assign io_uart_aw_bits_addr = 32'h0;
  assign io_uart_aw_bits_len = 8'h0;
  assign io_uart_aw_bits_size = 3'b000;
  assign io_uart_aw_bits_burst = 2'b00;
  assign io_uart_w_bits_data = 32'h0;
  assign io_uart_w_bits_strb = 4'b0000;
  assign io_uart_w_bits_last = 1'b0;
  assign io_dpy0 = 8'h0;
  assign io_dpy1 = 8'h0;
  always @(posedge clk) begin
    if(reset) begin
      state <= TestState_S_WRITE;
      timer_value <= 25'h0;
      writeAddr <= 20'h0;
      writeData <= 32'ha0a0a0a0;
      ledDisplay <= 16'h0;
    end else begin
      timer_value <= timer_valueNext;
      case(state)
        TestState_S_WRITE : begin
          state <= TestState_S_WAIT_AFTER_WRITE;
        end
        TestState_S_WAIT_AFTER_WRITE : begin
          if(timer_willOverflow) begin
            state <= TestState_S_READ;
          end
        end
        TestState_S_READ : begin
          state <= TestState_S_WAIT_AFTER_READ;
        end
        default : begin
          ledDisplay <= io_dsram_dout[15 : 0];
          if(timer_willOverflow) begin
            state <= TestState_S_WRITE;
            writeData <= _zz_writeData;
            writeAddr <= (writeAddr + 20'h00001);
          end
        end
      endcase
    end
  end


endmodule
