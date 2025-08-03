// Generator : SpinalHDL dev    git head : 49a99dae7b6ed938ae50042417514f24dcaeaaa8
// Component : UartAxiController
// Git hash  : 87d3fae701bed4f855cd869eaa5cc8644feef746

`timescale 1ns/1ps

module UartAxiController (
  input  wire          io_axi_aw_valid,
  output reg           io_axi_aw_ready,
  input  wire [31:0]   io_axi_aw_payload_addr,
  input  wire [7:0]    io_axi_aw_payload_id,
  input  wire [7:0]    io_axi_aw_payload_len,
  input  wire [2:0]    io_axi_aw_payload_size,
  input  wire [1:0]    io_axi_aw_payload_burst,
  input  wire          io_axi_w_valid,
  output reg           io_axi_w_ready,
  input  wire [31:0]   io_axi_w_payload_data,
  input  wire [3:0]    io_axi_w_payload_strb,
  input  wire          io_axi_w_payload_last,
  output wire          io_axi_b_valid,
  input  wire          io_axi_b_ready,
  output wire [7:0]    io_axi_b_payload_id,
  output wire [1:0]    io_axi_b_payload_resp,
  input  wire          io_axi_ar_valid,
  output reg           io_axi_ar_ready,
  input  wire [31:0]   io_axi_ar_payload_addr,
  input  wire [7:0]    io_axi_ar_payload_id,
  input  wire [7:0]    io_axi_ar_payload_len,
  input  wire [2:0]    io_axi_ar_payload_size,
  input  wire [1:0]    io_axi_ar_payload_burst,
  output wire          io_axi_r_valid,
  input  wire          io_axi_r_ready,
  output wire [31:0]   io_axi_r_payload_data,
  output wire [7:0]    io_axi_r_payload_id,
  output wire [1:0]    io_axi_r_payload_resp,
  output wire          io_axi_r_payload_last,
  output wire          io_txd,
  input  wire          io_rxd,
  input  wire          clk,
  input  wire          reset
);

  reg                 rxFifo_io_pop_ready;
  wire                uartRx_RxD_data_ready;
  wire       [7:0]    uartRx_RxD_data;
  wire                uartTx_TxD;
  wire                uartTx_TxD_busy;
  wire                rxFifo_io_push_ready;
  wire                rxFifo_io_pop_valid;
  wire       [7:0]    rxFifo_io_pop_payload;
  wire       [3:0]    rxFifo_io_occupancy;
  wire       [3:0]    rxFifo_io_availability;
  wire       [3:0]    _zz__zz_r_data;
  reg                 rxStarted;
  wire                when_UartAxiController_l146;
  wire                genuine_data_ready;
  wire                io_push_fire;
  reg        [7:0]    txData;
  reg                 txStart;
  reg                 r_valid;
  reg        [31:0]   r_data;
  reg        [1:0]    r_resp;
  reg        [7:0]    r_id;
  reg                 b_valid;
  reg        [1:0]    b_resp;
  reg        [7:0]    b_id;
  wire                io_axi_r_fire;
  wire                io_axi_b_fire;
  wire                aw_fire;
  wire                ar_fire;
  wire                w_fire;
  wire       [0:0]    readAddress;
  wire       [0:0]    writeAddress;
  wire                isDataAddrRead;
  wire                isStatusAddrRead;
  wire                isDataAddrWrite;
  wire                isStatusAddrWrite;
  wire                when_UartAxiController_l241;
  reg        [31:0]   _zz_r_data;
  wire                writeReady;
  wire                when_UartAxiController_l281;
  wire                when_UartAxiController_l286;
  wire       [3:0]    _zz_1;
  wire                when_UartAxiController_l328;
  wire                when_UartAxiController_l332;
  wire                when_UartAxiController_l336;
  wire       [7:0]    _zz_2;
  wire                when_UartAxiController_l352;

  assign _zz__zz_r_data = rxFifo_io_occupancy;
  async_receiver #(
    .ClkFrequency (100000000),
    .Baud         (9600     )
  ) uartRx (
    .clk            (clk                  ), //i
    .RxD            (io_rxd               ), //i
    .RxD_data_ready (uartRx_RxD_data_ready), //o
    .RxD_clear      (io_push_fire         ), //i
    .RxD_data       (uartRx_RxD_data[7:0] )  //o
  );
  async_transmitter #(
    .ClkFrequency (100000000),
    .Baud         (9600     )
  ) uartTx (
    .clk       (clk            ), //i
    .TxD       (uartTx_TxD     ), //o
    .TxD_busy  (uartTx_TxD_busy), //o
    .TxD_start (txStart        ), //i
    .TxD_data  (txData[7:0]    )  //i
  );
  StreamFifo_UartRx rxFifo (
    .io_push_valid   (genuine_data_ready         ), //i
    .io_push_ready   (rxFifo_io_push_ready       ), //o
    .io_push_payload (uartRx_RxD_data[7:0]       ), //i
    .io_pop_valid    (rxFifo_io_pop_valid        ), //o
    .io_pop_ready    (rxFifo_io_pop_ready        ), //i
    .io_pop_payload  (rxFifo_io_pop_payload[7:0] ), //o
    .io_flush        (1'b0                       ), //i
    .io_occupancy    (rxFifo_io_occupancy[3:0]   ), //o
    .io_availability (rxFifo_io_availability[3:0]), //o
    .clk             (clk                        ), //i
    .reset           (reset                      )  //i
  );
  assign io_txd = (uartTx_TxD_busy ? uartTx_TxD : 1'b1);
  assign when_UartAxiController_l146 = (! io_rxd);
  assign genuine_data_ready = (uartRx_RxD_data_ready && rxStarted);
  assign io_push_fire = (genuine_data_ready && rxFifo_io_push_ready);
  always @(*) begin
    io_axi_ar_ready = 1'b0;
    if(when_UartAxiController_l241) begin
      io_axi_ar_ready = 1'b1;
    end
  end

  assign io_axi_r_valid = r_valid;
  assign io_axi_r_payload_data = r_data;
  assign io_axi_r_payload_resp = r_resp;
  assign io_axi_r_payload_id = r_id;
  assign io_axi_r_payload_last = 1'b1;
  always @(*) begin
    io_axi_aw_ready = 1'b0;
    if(when_UartAxiController_l281) begin
      io_axi_aw_ready = writeReady;
    end
  end

  always @(*) begin
    io_axi_w_ready = 1'b0;
    if(when_UartAxiController_l281) begin
      io_axi_w_ready = writeReady;
    end
  end

  assign io_axi_b_valid = b_valid;
  assign io_axi_b_payload_resp = b_resp;
  assign io_axi_b_payload_id = b_id;
  assign io_axi_r_fire = (io_axi_r_valid && io_axi_r_ready);
  assign io_axi_b_fire = (io_axi_b_valid && io_axi_b_ready);
  assign aw_fire = (io_axi_aw_valid && io_axi_aw_ready);
  assign ar_fire = (io_axi_ar_valid && io_axi_ar_ready);
  assign w_fire = (io_axi_w_valid && io_axi_w_ready);
  assign readAddress = io_axi_ar_payload_addr[2 : 2];
  assign writeAddress = io_axi_aw_payload_addr[2 : 2];
  assign isDataAddrRead = (readAddress == 1'b0);
  assign isStatusAddrRead = (readAddress == 1'b1);
  assign isDataAddrWrite = (writeAddress == 1'b0);
  assign isStatusAddrWrite = (writeAddress == 1'b1);
  always @(*) begin
    rxFifo_io_pop_ready = 1'b0;
    if(when_UartAxiController_l241) begin
      if(ar_fire) begin
        if(isDataAddrRead) begin
          rxFifo_io_pop_ready = 1'b1;
        end
      end
    end
  end

  assign when_UartAxiController_l241 = (! r_valid);
  always @(*) begin
    _zz_r_data = 32'h0;
    _zz_r_data[0] = (! uartTx_TxD_busy);
    _zz_r_data[1] = rxFifo_io_pop_valid;
    _zz_r_data[15 : 8] = {4'd0, _zz__zz_r_data};
  end

  assign writeReady = ((! b_valid) && (! uartTx_TxD_busy));
  assign when_UartAxiController_l281 = (! b_valid);
  assign when_UartAxiController_l286 = (aw_fire && w_fire);
  assign _zz_1 = (rxFifo_io_occupancy + 4'b0001);
  assign when_UartAxiController_l328 = (isDataAddrRead && ar_fire);
  assign when_UartAxiController_l332 = (isStatusAddrRead && ar_fire);
  assign when_UartAxiController_l336 = (io_axi_r_fire && r_valid);
  assign _zz_2 = io_axi_w_payload_data[7 : 0];
  assign when_UartAxiController_l352 = (io_axi_b_fire && b_valid);
  always @(posedge clk) begin
    if(reset) begin
      rxStarted <= 1'b0;
      txData <= 8'h0;
      txStart <= 1'b0;
      r_valid <= 1'b0;
      r_data <= 32'h0;
      r_resp <= 2'b00;
      r_id <= 8'h0;
      b_valid <= 1'b0;
      b_resp <= 2'b00;
      b_id <= 8'h0;
    end else begin
      if(when_UartAxiController_l146) begin
        rxStarted <= 1'b1;
      end
      if(reset) begin
        rxStarted <= 1'b0;
      end
      if(io_axi_r_fire) begin
        r_valid <= 1'b0;
      end
      if(io_axi_b_fire) begin
        b_valid <= 1'b0;
      end
      if(txStart) begin
        txStart <= 1'b0;
      end
      if(when_UartAxiController_l241) begin
        if(ar_fire) begin
          r_valid <= 1'b1;
          r_resp <= 2'b00;
          r_id <= io_axi_ar_payload_id;
          if(isDataAddrRead) begin
            r_data <= {24'd0, rxFifo_io_pop_payload};
          end else begin
            if(isStatusAddrRead) begin
              r_data <= _zz_r_data;
            end else begin
              r_resp <= 2'b10;
              r_data <= 32'h0;
            end
          end
        end
      end
      if(when_UartAxiController_l281) begin
        if(when_UartAxiController_l286) begin
          b_valid <= 1'b1;
          b_resp <= 2'b00;
          b_id <= io_axi_aw_payload_id;
          if(isDataAddrWrite) begin
            txData <= io_axi_w_payload_data[7 : 0];
            txStart <= 1'b1;
          end else begin
            if(isStatusAddrWrite) begin
              b_resp <= 2'b10;
            end else begin
              b_resp <= 2'b10;
            end
          end
        end
      end
      if(io_axi_ar_valid) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert((io_axi_ar_payload_len == 8'h0)); // UartAxiController.scala:L306
          `else
            if(!(io_axi_ar_payload_len == 8'h0)) begin
              $display("FAILURE UartAxiController received a burst read request (ARLEN != 0), which is not supported!"); // UartAxiController.scala:L306
              $finish;
            end
          `endif
        `endif
      end
      if(io_axi_aw_valid) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert((io_axi_aw_payload_len == 8'h0)); // UartAxiController.scala:L310
          `else
            if(!(io_axi_aw_payload_len == 8'h0)) begin
              $display("FAILURE UartAxiController received a burst write request (AWLEN != 0), which is not supported!"); // UartAxiController.scala:L310
              $finish;
            end
          `endif
        `endif
      end
      if(uartRx_RxD_data_ready) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L317
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:317):  UART_CTRL: async_receiver data ready! Data: 0x%x", uartRx_RxD_data); // UartAxiController.scala:L317
            end
          `endif
        `endif
      end
      if(io_push_fire) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L321
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:321):  UART_CTRL: rxFifo PUSH fired! Occupancy is now: %x", _zz_1); // UartAxiController.scala:L321
            end
          `endif
        `endif
      end
      if(ar_fire) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L325
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:325):  UART_CTRL: AXI Read Request (AR) fired! Addr: 0x%x", io_axi_ar_payload_addr); // UartAxiController.scala:L325
            end
          `endif
        `endif
      end
      if(when_UartAxiController_l328) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L329
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:329):  UART_CTRL: Reading from DATA register. FIFO valid=%x", rxFifo_io_pop_valid); // UartAxiController.scala:L329
            end
          `endif
        `endif
      end
      if(when_UartAxiController_l332) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L333
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:333):  UART_CTRL: Reading from STATUS register."); // UartAxiController.scala:L333
            end
          `endif
        `endif
      end
      if(when_UartAxiController_l336) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L337
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:337):  UART_CTRL: AXI Read Response (R) fired! Data: 0x%x", io_axi_r_payload_data); // UartAxiController.scala:L337
            end
          `endif
        `endif
      end
      if(aw_fire) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L341
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:341):  UART_CTRL: AXI Write Address (AW) fired! Addr: 0x%x", io_axi_aw_payload_addr); // UartAxiController.scala:L341
            end
          `endif
        `endif
      end
      if(w_fire) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L345
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:345):  UART_CTRL: AXI Write Data (W) fired! Data: 0x%x, Strobe: %x", _zz_2, io_axi_w_payload_strb); // UartAxiController.scala:L345
            end
          `endif
        `endif
      end
      if(txStart) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L349
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:349):  UART_CTRL: txStart pulse generated! Sending 0x%x to async_transmitter.", txData); // UartAxiController.scala:L349
            end
          `endif
        `endif
      end
      if(when_UartAxiController_l352) begin
        `ifndef SYNTHESIS
          `ifdef FORMAL
            assert(1'b0); // UartAxiController.scala:L353
          `else
            if(!1'b0) begin
              $display("NOTE(UartAxiController.scala:353):  UART_CTRL: AXI Write Response (B) fired!"); // UartAxiController.scala:L353
            end
          `endif
        `endif
      end
    end
  end


endmodule

module StreamFifo_UartRx (
  input  wire          io_push_valid,
  output wire          io_push_ready,
  input  wire [7:0]    io_push_payload,
  output wire          io_pop_valid,
  input  wire          io_pop_ready,
  output wire [7:0]    io_pop_payload,
  input  wire          io_flush,
  output wire [3:0]    io_occupancy,
  output wire [3:0]    io_availability,
  input  wire          clk,
  input  wire          reset
);

  reg        [7:0]    logic_ram_spinal_port1;
  reg                 _zz_1;
  wire                logic_ptr_doPush;
  wire                logic_ptr_doPop;
  wire                logic_ptr_full;
  wire                logic_ptr_empty;
  reg        [3:0]    logic_ptr_push;
  reg        [3:0]    logic_ptr_pop;
  wire       [3:0]    logic_ptr_occupancy;
  wire       [3:0]    logic_ptr_popOnIo;
  wire                when_Stream_l1455;
  reg                 logic_ptr_wentUp;
  wire                io_push_fire;
  wire                logic_push_onRam_write_valid;
  wire       [2:0]    logic_push_onRam_write_payload_address;
  wire       [7:0]    logic_push_onRam_write_payload_data;
  wire                logic_pop_addressGen_valid;
  reg                 logic_pop_addressGen_ready;
  wire       [2:0]    logic_pop_addressGen_payload;
  wire                logic_pop_addressGen_fire;
  wire                logic_pop_sync_readArbitation_valid;
  wire                logic_pop_sync_readArbitation_ready;
  wire       [2:0]    logic_pop_sync_readArbitation_payload;
  reg                 logic_pop_addressGen_rValid;
  reg        [2:0]    logic_pop_addressGen_rData;
  wire                when_Stream_l477;
  wire                logic_pop_sync_readPort_cmd_valid;
  wire       [2:0]    logic_pop_sync_readPort_cmd_payload;
  wire       [7:0]    logic_pop_sync_readPort_rsp;
  wire                logic_pop_addressGen_toFlowFire_valid;
  wire       [2:0]    logic_pop_addressGen_toFlowFire_payload;
  wire                logic_pop_sync_readArbitation_translated_valid;
  wire                logic_pop_sync_readArbitation_translated_ready;
  wire       [7:0]    logic_pop_sync_readArbitation_translated_payload;
  wire                logic_pop_sync_readArbitation_fire;
  reg        [3:0]    logic_pop_sync_popReg;
  (* ram_style = "block" *) reg [7:0] logic_ram [0:7];

  always @(posedge clk) begin
    if(_zz_1) begin
      logic_ram[logic_push_onRam_write_payload_address] <= logic_push_onRam_write_payload_data;
    end
  end

  always @(posedge clk) begin
    if(logic_pop_sync_readPort_cmd_valid) begin
      logic_ram_spinal_port1 <= logic_ram[logic_pop_sync_readPort_cmd_payload];
    end
  end

  always @(*) begin
    _zz_1 = 1'b0;
    if(logic_push_onRam_write_valid) begin
      _zz_1 = 1'b1;
    end
  end

  assign when_Stream_l1455 = (logic_ptr_doPush != logic_ptr_doPop);
  assign logic_ptr_full = (((logic_ptr_push ^ logic_ptr_popOnIo) ^ 4'b1000) == 4'b0000);
  assign logic_ptr_empty = (logic_ptr_push == logic_ptr_pop);
  assign logic_ptr_occupancy = (logic_ptr_push - logic_ptr_popOnIo);
  assign io_push_ready = (! logic_ptr_full);
  assign io_push_fire = (io_push_valid && io_push_ready);
  assign logic_ptr_doPush = io_push_fire;
  assign logic_push_onRam_write_valid = io_push_fire;
  assign logic_push_onRam_write_payload_address = logic_ptr_push[2:0];
  assign logic_push_onRam_write_payload_data = io_push_payload;
  assign logic_pop_addressGen_valid = (! logic_ptr_empty);
  assign logic_pop_addressGen_payload = logic_ptr_pop[2:0];
  assign logic_pop_addressGen_fire = (logic_pop_addressGen_valid && logic_pop_addressGen_ready);
  assign logic_ptr_doPop = logic_pop_addressGen_fire;
  always @(*) begin
    logic_pop_addressGen_ready = logic_pop_sync_readArbitation_ready;
    if(when_Stream_l477) begin
      logic_pop_addressGen_ready = 1'b1;
    end
  end

  assign when_Stream_l477 = (! logic_pop_sync_readArbitation_valid);
  assign logic_pop_sync_readArbitation_valid = logic_pop_addressGen_rValid;
  assign logic_pop_sync_readArbitation_payload = logic_pop_addressGen_rData;
  assign logic_pop_sync_readPort_rsp = logic_ram_spinal_port1;
  assign logic_pop_addressGen_toFlowFire_valid = logic_pop_addressGen_fire;
  assign logic_pop_addressGen_toFlowFire_payload = logic_pop_addressGen_payload;
  assign logic_pop_sync_readPort_cmd_valid = logic_pop_addressGen_toFlowFire_valid;
  assign logic_pop_sync_readPort_cmd_payload = logic_pop_addressGen_toFlowFire_payload;
  assign logic_pop_sync_readArbitation_translated_valid = logic_pop_sync_readArbitation_valid;
  assign logic_pop_sync_readArbitation_ready = logic_pop_sync_readArbitation_translated_ready;
  assign logic_pop_sync_readArbitation_translated_payload = logic_pop_sync_readPort_rsp;
  assign io_pop_valid = logic_pop_sync_readArbitation_translated_valid;
  assign logic_pop_sync_readArbitation_translated_ready = io_pop_ready;
  assign io_pop_payload = logic_pop_sync_readArbitation_translated_payload;
  assign logic_pop_sync_readArbitation_fire = (logic_pop_sync_readArbitation_valid && logic_pop_sync_readArbitation_ready);
  assign logic_ptr_popOnIo = logic_pop_sync_popReg;
  assign io_occupancy = logic_ptr_occupancy;
  assign io_availability = (4'b1000 - logic_ptr_occupancy);
  always @(posedge clk) begin
    if(reset) begin
      logic_ptr_push <= 4'b0000;
      logic_ptr_pop <= 4'b0000;
      logic_ptr_wentUp <= 1'b0;
      logic_pop_addressGen_rValid <= 1'b0;
      logic_pop_sync_popReg <= 4'b0000;
    end else begin
      if(when_Stream_l1455) begin
        logic_ptr_wentUp <= logic_ptr_doPush;
      end
      if(io_flush) begin
        logic_ptr_wentUp <= 1'b0;
      end
      if(logic_ptr_doPush) begin
        logic_ptr_push <= (logic_ptr_push + 4'b0001);
      end
      if(logic_ptr_doPop) begin
        logic_ptr_pop <= (logic_ptr_pop + 4'b0001);
      end
      if(io_flush) begin
        logic_ptr_push <= 4'b0000;
        logic_ptr_pop <= 4'b0000;
      end
      if(logic_pop_addressGen_ready) begin
        logic_pop_addressGen_rValid <= logic_pop_addressGen_valid;
      end
      if(io_flush) begin
        logic_pop_addressGen_rValid <= 1'b0;
      end
      if(logic_pop_sync_readArbitation_fire) begin
        logic_pop_sync_popReg <= logic_ptr_pop;
      end
      if(io_flush) begin
        logic_pop_sync_popReg <= 4'b0000;
      end
    end
  end

  always @(posedge clk) begin
    if(logic_pop_addressGen_ready) begin
      logic_pop_addressGen_rData <= logic_pop_addressGen_payload;
    end
  end


endmodule
