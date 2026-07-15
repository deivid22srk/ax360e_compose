/**
 ******************************************************************************
 * Xenia : Xbox 360 Emulator Research Project                                 *
 ******************************************************************************
 * Copyright 2022 Ben Vanik. All rights reserved.                             *
 * Released under the BSD license - see LICENSE in the root for more details. *
 ******************************************************************************
 */

#include <cstring>

#include "xenia/base/logging.h"
#include "xenia/kernel/kernel_state.h"
#include "xenia/kernel/util/shim_utils.h"
#include "xenia/kernel/xam/xam_module.h"
#include "xenia/kernel/xam/xam_private.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_error.h"
#include "xenia/kernel/xboxkrnl/xboxkrnl_threading.h"
#include "xenia/kernel/xevent.h"
#include "xenia/kernel/xsocket.h"
#include "xenia/kernel/xthread.h"
#include "xenia/xbox.h"

#ifdef XE_PLATFORM_WIN32
// NOTE: must be included last as it expects windows.h to already be included.
#define _WINSOCK_DEPRECATED_NO_WARNINGS  // inet_addr
#include <winsock2.h>                    // NOLINT(build/include_order)
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <sys/select.h>
#include <sys/socket.h>
#endif

namespace xe {
namespace kernel {
namespace xam {

// https://github.com/G91/TitanOffLine/blob/1e692d9bb9dfac386d08045ccdadf4ae3227bb5e/xkelib/xam/xamNet.h
enum {
  XNCALLER_INVALID = 0x0,
  XNCALLER_TITLE = 0x1,
  XNCALLER_SYSAPP = 0x2,
  XNCALLER_XBDM = 0x3,
  XNCALLER_TEST = 0x4,
  NUM_XNCALLER_TYPES = 0x4,
};

// https://github.com/pmrowla/hl2sdk-csgo/blob/master/common/xbox/xboxstubs.h
typedef struct {
  // FYI: IN_ADDR should be in network-byte order.
  in_addr ina;                   // IP address (zero if not static/DHCP)
  in_addr inaOnline;             // Online IP address (zero if not online)
  xe::be<uint16_t> wPortOnline;  // Online port
  uint8_t abEnet[6];             // Ethernet MAC address
  uint8_t abOnline[20];          // Online identification
} XNADDR;

struct XNDNS {
  xe::be<int32_t> status;
  xe::be<uint32_t> cina;
  in_addr aina[8];
};
static_assert_size(XNDNS, 0x28);

struct XNQOSINFO {
  uint8_t flags;
  uint8_t reserved;
  xe::be<uint16_t> probes_xmit;
  xe::be<uint16_t> probes_recv;
  xe::be<uint16_t> data_len;
  xe::be<uint32_t> data_ptr;
  xe::be<uint16_t> rtt_min_in_msecs;
  xe::be<uint16_t> rtt_med_in_msecs;
  xe::be<uint32_t> up_bits_per_sec;
  xe::be<uint32_t> down_bits_per_sec;
};
static_assert_size(XNQOSINFO, 0x18);

struct XNQOS {
  xe::be<uint32_t> count;
  xe::be<uint32_t> count_pending;
  XNQOSINFO info[1];
};

struct Xsockaddr_t {
  xe::be<uint16_t> sa_family;
  char sa_data[14];
};
static_assert_size(XNQOS, 0x20);

struct X_WSADATA {
  xe::be<uint16_t> version;
  xe::be<uint16_t> version_high;
  char description[256 + 1];
  char system_status[128 + 1];
  xe::be<uint16_t> max_sockets;
  xe::be<uint16_t> max_udpdg;
  xe::be<uint32_t> vendor_info_ptr;
};
static_assert_size(X_WSADATA, 0x190);

struct XWSABUF {
  xe::be<uint32_t> len;
  xe::be<uint32_t> buf_ptr;
};

struct XWSAOVERLAPPED {
  xe::be<uint32_t> internal;
  xe::be<uint32_t> internal_high;
  union {
    struct {
      xe::be<uint32_t> low;
      xe::be<uint32_t> high;
    } offset;  // must be named to avoid GCC error
    xe::be<uint32_t> pointer;
  };
  xe::be<uint32_t> event_handle;
};

void LoadSockaddr(const uint8_t* ptr, sockaddr* out_addr) {
  out_addr->sa_family = xe::load_and_swap<uint16_t>(ptr + 0);
  switch (out_addr->sa_family) {
    case AF_INET: {
      auto in_addr = reinterpret_cast<sockaddr_in*>(out_addr);
      in_addr->sin_port = xe::load_and_swap<uint16_t>(ptr + 2);
      // Maybe? Depends on type.
      in_addr->sin_addr.s_addr = *(uint32_t*)(ptr + 4);
      break;
    }
    default:
      assert_unhandled_case(out_addr->sa_family);
      break;
  }
}

void StoreSockaddr(const sockaddr& addr, uint8_t* ptr) {
  switch (addr.sa_family) {
    case AF_UNSPEC:
      std::memset(ptr, 0, sizeof(addr));
      break;
    case AF_INET: {
      auto& in_addr = reinterpret_cast<const sockaddr_in&>(addr);
      xe::store_and_swap<uint16_t>(ptr + 0, in_addr.sin_family);
      xe::store_and_swap<uint16_t>(ptr + 2, in_addr.sin_port);
      // Maybe? Depends on type.
      xe::store_and_swap<uint32_t>(ptr + 4, in_addr.sin_addr.s_addr);
      break;
    }
    default:
      assert_unhandled_case(addr.sa_family);
      break;
  }
}

// https://github.com/joolswills/mameox/blob/master/MAMEoX/Sources/xbox_Network.cpp#L136
struct XNetStartupParams {
  uint8_t cfgSizeOfStruct;
  uint8_t cfgFlags;
  uint8_t cfgSockMaxDgramSockets;
  uint8_t cfgSockMaxStreamSockets;
  uint8_t cfgSockDefaultRecvBufsizeInK;
  uint8_t cfgSockDefaultSendBufsizeInK;
  uint8_t cfgKeyRegMax;
  uint8_t cfgSecRegMax;
  uint8_t cfgQosDataLimitDiv4;
  uint8_t cfgQosProbeTimeoutInSeconds;
  uint8_t cfgQosProbeRetries;
  uint8_t cfgQosSrvMaxSimultaneousResponses;
  uint8_t cfgQosPairWaitTimeInSeconds;
};
static_assert_size(XNetStartupParams, 0xD);

XNetStartupParams xnet_startup_params = {0};

dword_result_t NetDll_XNetStartup_entry(dword_t caller,
                                        pointer_t<XNetStartupParams> params) {
  if (params) {
    assert_true(params->cfgSizeOfStruct == sizeof(XNetStartupParams));
    std::memcpy(&xnet_startup_params, params, sizeof(XNetStartupParams));
  }

  auto xam = kernel_state()->GetKernelModule<XamModule>("xam.xex");

  /*
  if (!xam->xnet()) {
    auto xnet = new XNet(kernel_state());
    xnet->Initialize();

    xam->set_xnet(xnet);
  }
  */

  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetStartup, kNetworking, kImplemented);

// https://github.com/jogolden/testdev/blob/master/xkelib/syssock.h#L46
dword_result_t NetDll_XNetStartupEx_entry(dword_t caller,
                                          pointer_t<XNetStartupParams> params,
                                          dword_t versionReq) {
  // versionReq
  // MW3, Ghosts: 0x20501400

  return NetDll_XNetStartup_entry(caller, params);
}
DECLARE_XAM_EXPORT1(NetDll_XNetStartupEx, kNetworking, kImplemented);

dword_result_t NetDll_XNetCleanup_entry(dword_t caller, lpvoid_t params) {
  auto xam = kernel_state()->GetKernelModule<XamModule>("xam.xex");
  // auto xnet = xam->xnet();
  // xam->set_xnet(nullptr);

  // TODO: Shut down and delete.
  // delete xnet;

  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetCleanup, kNetworking, kImplemented);

dword_result_t NetDll_XNetGetOpt_entry(dword_t one, dword_t option_id,
                                       lpvoid_t buffer_ptr,
                                       lpdword_t buffer_size) {
  assert_true(one == 1);
  switch (option_id) {
    case 1:
      if (*buffer_size < sizeof(XNetStartupParams)) {
        *buffer_size = sizeof(XNetStartupParams);
        return uint32_t(X_WSAError::X_WSAEMSGSIZE);
      }
      std::memcpy(buffer_ptr, &xnet_startup_params, sizeof(XNetStartupParams));
      return 0;
    default:
      XELOGE("NetDll_XNetGetOpt: option {} unimplemented",
             static_cast<uint32_t>(option_id));
      return uint32_t(X_WSAError::X_WSAEINVAL);
  }
}
DECLARE_XAM_EXPORT1(NetDll_XNetGetOpt, kNetworking, kSketchy);

dword_result_t NetDll_XNetRandom_entry(dword_t caller, lpvoid_t buffer_ptr,
                                       dword_t length) {
  uint8_t* buffer_data_ptr = buffer_ptr.as<uint8_t*>();

  if (buffer_data_ptr == nullptr || length == 0) {
    return X_ERROR_SUCCESS;
  }

  std::random_device rnd;
  std::mt19937_64 gen(rnd());
  std::uniform_int_distribution<int> dist(0,
                                          std::numeric_limits<uint8_t>::max());

  std::generate(buffer_data_ptr, buffer_data_ptr + length,
                [&]() { return static_cast<uint8_t>(dist(gen)); });

  return X_ERROR_SUCCESS;
}
DECLARE_XAM_EXPORT1(NetDll_XNetRandom, kNetworking, kImplemented);

dword_result_t NetDll_WSAStartup_entry(dword_t caller, word_t version,
                                       pointer_t<X_WSADATA> data_ptr) {
  // TODO(benvanik): abstraction layer needed.
  int ret = 0;

#ifdef XE_PLATFORM_WIN32
  WSADATA wsaData = {};

  ret = WSAStartup(version, &wsaData);
#endif

  if (data_ptr) {
    data_ptr.Zero();

#ifdef XE_PLATFORM_WIN32
    data_ptr->version = wsaData.wVersion;
    data_ptr->version_high = wsaData.wHighVersion;
#else
    data_ptr->version = version.value();
    data_ptr->version_high = 0x0202;
#endif
  }

  // DEBUG
  /*
  auto xam = kernel_state()->GetKernelModule<XamModule>("xam.xex");
  if (!xam->xnet()) {
    auto xnet = new XNet(kernel_state());
    xnet->Initialize();

    xam->set_xnet(xnet);
  }
  */

  return ret;
}
DECLARE_XAM_EXPORT1(NetDll_WSAStartup, kNetworking, kImplemented);

dword_result_t NetDll_WSAStartupEx_entry(dword_t caller, word_t version,
                                         pointer_t<X_WSADATA> data_ptr,
                                         dword_t versionReq) {
  return NetDll_WSAStartup_entry(caller, version, data_ptr);
}
DECLARE_XAM_EXPORT1(NetDll_WSAStartupEx, kNetworking, kImplemented);

dword_result_t NetDll_WSACleanup_entry(dword_t caller) {
  // This does nothing. Xenia needs WSA running.
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_WSACleanup, kNetworking, kImplemented);

// Instead of using dedicated storage for WSA error like on OS.
// Xbox shares space between normal error codes and WSA errors.
// This under the hood returns directly value received from RtlGetLastError.
dword_result_t NetDll_WSAGetLastError_entry() {
  uint32_t last_error = XThread::GetLastError();
  XELOGD("NetDll_WSAGetLastError: {}", last_error);
  return last_error;
}
DECLARE_XAM_EXPORT1(NetDll_WSAGetLastError, kNetworking, kImplemented);

dword_result_t NetDll_WSARecvFrom_entry(
    dword_t caller, dword_t socket, pointer_t<XWSABUF> buffers_ptr,
    dword_t buffer_count, lpdword_t num_bytes_recv, lpdword_t flags_ptr,
    pointer_t<XSOCKADDR_IN> from_addr, pointer_t<XWSAOVERLAPPED> overlapped_ptr,
    lpvoid_t completion_routine_ptr) {
  if (overlapped_ptr) {
    // auto evt = kernel_state()->object_table()->LookupObject<XEvent>(
    //    overlapped_ptr->event_handle);

    // if (evt) {
    //  //evt->Set(0, false);
    //}
  }

  // we're not going to be receiving packets any time soon
  // return error so we don't wait on that - Cancerous
  return -1;
}
DECLARE_XAM_EXPORT2(NetDll_WSARecvFrom, kNetworking, kStub, kHighFrequency);

// If the socket is a VDP socket, buffer 0 is the game data length, and buffer 1
// is the unencrypted game data.
dword_result_t NetDll_WSASendTo_entry(
    dword_t caller, dword_t socket_handle, pointer_t<XWSABUF> buffers,
    dword_t num_buffers, lpdword_t num_bytes_sent, dword_t flags,
    pointer_t<XSOCKADDR_IN> to_ptr, dword_t to_len,
    pointer_t<XWSAOVERLAPPED> overlapped, lpvoid_t completion_routine) {
  assert(!overlapped);
  assert(!completion_routine);

  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  // Our sockets implementation doesn't support multiple buffers, so we need
  // to combine the buffers the game has given us!
  std::vector<uint8_t> combined_buffer_mem;
  uint32_t combined_buffer_size = 0;
  uint32_t combined_buffer_offset = 0;
  for (uint32_t i = 0; i < num_buffers; i++) {
    combined_buffer_size += buffers[i].len;
    combined_buffer_mem.resize(combined_buffer_size);
    uint8_t* combined_buffer = combined_buffer_mem.data();

    std::memcpy(combined_buffer + combined_buffer_offset,
                kernel_memory()->TranslateVirtual(buffers[i].buf_ptr),
                buffers[i].len);
    combined_buffer_offset += buffers[i].len;
  }

  N_XSOCKADDR_IN native_to(to_ptr);
  socket->SendTo(combined_buffer_mem.data(), combined_buffer_size, flags,
                 &native_to, to_len);

  // TODO: Instantly complete overlapped

  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_WSASendTo, kNetworking, kImplemented);

dword_result_t NetDll_WSAWaitForMultipleEvents_entry(dword_t num_events,
                                                     lpdword_t events,
                                                     dword_t wait_all,
                                                     dword_t timeout,
                                                     dword_t alertable) {
  if (num_events > 64) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSA_INVALID_PARAMETER));
    return ~0u;
  }

  uint64_t timeout_wait = (uint64_t)timeout;

  X_STATUS result = 0;
  do {
    result = xboxkrnl::xeNtWaitForMultipleObjectsEx(
        num_events, events, wait_all, 1, alertable,
        timeout != -1 ? &timeout_wait : nullptr);
  } while (result == X_STATUS_ALERTED);

  if (XFAILED(result)) {
    uint32_t error = xboxkrnl::xeRtlNtStatusToDosError(result);
    XThread::SetLastError(error);
    return ~0u;
  }
  return 0;
}
DECLARE_XAM_EXPORT2(NetDll_WSAWaitForMultipleEvents, kNetworking, kImplemented,
                    kBlocking);

dword_result_t NetDll_WSACreateEvent_entry() {
  XEvent* ev = new XEvent(kernel_state());
  ev->Initialize(true, false);
  return ev->handle();
}
DECLARE_XAM_EXPORT1(NetDll_WSACreateEvent, kNetworking, kImplemented);

dword_result_t NetDll_WSACloseEvent_entry(dword_t event_handle) {
  X_STATUS result = kernel_state()->object_table()->ReleaseHandle(event_handle);
  if (XFAILED(result)) {
    uint32_t error = xboxkrnl::xeRtlNtStatusToDosError(result);
    XThread::SetLastError(error);
    return 0;
  }
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_WSACloseEvent, kNetworking, kImplemented);

dword_result_t NetDll_WSAResetEvent_entry(dword_t event_handle) {
  X_STATUS result = xboxkrnl::xeNtClearEvent(event_handle);
  if (XFAILED(result)) {
    uint32_t error = xboxkrnl::xeRtlNtStatusToDosError(result);
    XThread::SetLastError(error);
    return 0;
  }
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_WSAResetEvent, kNetworking, kImplemented);

dword_result_t NetDll_WSASetEvent_entry(dword_t event_handle) {
  X_STATUS result = xboxkrnl::xeNtSetEvent(event_handle, nullptr);
  if (XFAILED(result)) {
    uint32_t error = xboxkrnl::xeRtlNtStatusToDosError(result);
    XThread::SetLastError(error);
    return 0;
  }
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_WSASetEvent, kNetworking, kImplemented);

struct XnAddrStatus {
  // Address acquisition is not yet complete
  static constexpr uint32_t XNET_GET_XNADDR_PENDING = 0x00000000;
  // XNet is uninitialized or no debugger found
  static constexpr uint32_t XNET_GET_XNADDR_NONE = 0x00000001;
  // Host has ethernet address (no IP address)
  static constexpr uint32_t XNET_GET_XNADDR_ETHERNET = 0x00000002;
  // Host has statically assigned IP address
  static constexpr uint32_t XNET_GET_XNADDR_STATIC = 0x00000004;
  // Host has DHCP assigned IP address
  static constexpr uint32_t XNET_GET_XNADDR_DHCP = 0x00000008;
  // Host has PPPoE assigned IP address
  static constexpr uint32_t XNET_GET_XNADDR_PPPOE = 0x00000010;
  // Host has one or more gateways configured
  static constexpr uint32_t XNET_GET_XNADDR_GATEWAY = 0x00000020;
  // Host has one or more DNS servers configured
  static constexpr uint32_t XNET_GET_XNADDR_DNS = 0x00000040;
  // Host is currently connected to online service
  static constexpr uint32_t XNET_GET_XNADDR_ONLINE = 0x00000080;
  // Network configuration requires troubleshooting
  static constexpr uint32_t XNET_GET_XNADDR_TROUBLESHOOT = 0x00008000;
};

dword_result_t NetDll_XNetGetTitleXnAddr_entry(dword_t caller,
                                               pointer_t<XNADDR> addr_ptr) {
  // Just return a loopback address atm.
  addr_ptr->ina.s_addr = htonl(INADDR_LOOPBACK);
  addr_ptr->inaOnline.s_addr = 0;
  addr_ptr->wPortOnline = 0;

  // TODO(gibbed): A proper mac address.
  // RakNet's 360 version appears to depend on abEnet to create "random" 64-bit
  // numbers. A zero value will cause RakPeer::Startup to fail. This causes
  // 58411436 to crash on startup.
  // The 360-specific code is scrubbed from the RakNet repo, but there's still
  // traces of what it's doing which match the game code.
  // https://github.com/facebookarchive/RakNet/blob/master/Source/RakPeer.cpp#L382
  // https://github.com/facebookarchive/RakNet/blob/master/Source/RakPeer.cpp#L4527
  // https://github.com/facebookarchive/RakNet/blob/master/Source/RakPeer.cpp#L4467
  // "Mac address is a poor solution because you can't have multiple connections
  // from the same system"
  std::memset(addr_ptr->abEnet, 0xCC, 6);

  std::memset(addr_ptr->abOnline, 0, 20);

  return XnAddrStatus::XNET_GET_XNADDR_STATIC;
}
DECLARE_XAM_EXPORT1(NetDll_XNetGetTitleXnAddr, kNetworking, kStub);

dword_result_t NetDll_XNetGetDebugXnAddr_entry(dword_t caller,
                                               pointer_t<XNADDR> addr_ptr) {
  addr_ptr.Zero();

  // XNET_GET_XNADDR_NONE causes caller to gracefully return.
  return XnAddrStatus::XNET_GET_XNADDR_NONE;
}
DECLARE_XAM_EXPORT1(NetDll_XNetGetDebugXnAddr, kNetworking, kStub);

dword_result_t NetDll_XNetXnAddrToMachineId_entry(dword_t caller,
                                                  pointer_t<XNADDR> addr_ptr,
                                                  lpdword_t id_ptr) {
  // Tell the caller we're not signed in to live (non-zero ret)
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_XNetXnAddrToMachineId, kNetworking, kStub);

void NetDll_XNetInAddrToString_entry(dword_t caller, dword_t in_addr,
                                     lpstring_t string_out,
                                     dword_t string_size) {
  strncpy(string_out, "666.666.666.666", string_size);
}
DECLARE_XAM_EXPORT1(NetDll_XNetInAddrToString, kNetworking, kStub);

// This converts a XNet address to an IN_ADDR. The IN_ADDR is used for
// subsequent socket calls (like a handle to a XNet address)
dword_result_t NetDll_XNetXnAddrToInAddr_entry(dword_t caller,
                                               pointer_t<XNADDR> xn_addr,
                                               lpvoid_t xid, lpvoid_t in_addr) {
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_XNetXnAddrToInAddr, kNetworking, kStub);

// Does the reverse of the above.
// FIXME: Arguments may not be correct.
dword_result_t NetDll_XNetInAddrToXnAddr_entry(dword_t caller, lpvoid_t in_addr,
                                               pointer_t<XNADDR> xn_addr,
                                               lpvoid_t xid) {
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_XNetInAddrToXnAddr, kNetworking, kStub);

// https://www.google.com/patents/WO2008112448A1?cl=en
// Reserves a port for use by system link
dword_result_t NetDll_XNetSetSystemLinkPort_entry(dword_t caller,
                                                  dword_t port) {
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_XNetSetSystemLinkPort, kNetworking, kStub);

// https://github.com/ILOVEPIE/Cxbx-Reloaded/blob/master/src/CxbxKrnl/EmuXOnline.h#L39
struct XEthernetStatus {
  static constexpr uint32_t XNET_ETHERNET_LINK_ACTIVE = 0x01;
  static constexpr uint32_t XNET_ETHERNET_LINK_100MBPS = 0x02;
  static constexpr uint32_t XNET_ETHERNET_LINK_10MBPS = 0x04;
  static constexpr uint32_t XNET_ETHERNET_LINK_FULL_DUPLEX = 0x08;
  static constexpr uint32_t XNET_ETHERNET_LINK_HALF_DUPLEX = 0x10;
};

dword_result_t NetDll_XNetGetEthernetLinkStatus_entry(dword_t caller) {
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetGetEthernetLinkStatus, kNetworking, kStub);

dword_result_t NetDll_XNetDnsLookup_entry(dword_t caller, lpstring_t host,
                                          dword_t event_handle,
                                          lpdword_t pdns) {
  // TODO(gibbed): actually implement this
  if (pdns) {
    auto dns_guest = kernel_memory()->SystemHeapAlloc(sizeof(XNDNS));
    auto dns = kernel_memory()->TranslateVirtual<XNDNS*>(dns_guest);
    dns->status = 1;  // non-zero = error
    *pdns = dns_guest;
  }
  if (event_handle) {
    auto ev =
        kernel_state()->object_table()->LookupObject<XEvent>(event_handle);
    assert_not_null(ev);
    ev->Set(0, false);
  }
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetDnsLookup, kNetworking, kStub);

dword_result_t NetDll_XNetDnsRelease_entry(dword_t caller,
                                           pointer_t<XNDNS> dns) {
  if (!dns) {
    return X_STATUS_INVALID_PARAMETER;
  }
  kernel_memory()->SystemHeapFree(dns.guest_address());
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetDnsRelease, kNetworking, kStub);

dword_result_t NetDll_XNetQosServiceLookup_entry(dword_t caller, dword_t flags,
                                                 dword_t event_handle,
                                                 lpdword_t pqos) {
  // Set pqos as some games will try accessing it despite non-successful result
  if (pqos) {
    auto qos_guest = kernel_memory()->SystemHeapAlloc(sizeof(XNQOS));
    auto qos = kernel_memory()->TranslateVirtual<XNQOS*>(qos_guest);
    qos->count = qos->count_pending = 0;
    *pqos = qos_guest;
  }
  if (event_handle) {
    auto ev =
        kernel_state()->object_table()->LookupObject<XEvent>(event_handle);
    assert_not_null(ev);
    ev->Set(0, false);
  }
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetQosServiceLookup, kNetworking, kStub);

dword_result_t NetDll_XNetQosRelease_entry(dword_t caller,
                                           pointer_t<XNQOS> qos) {
  if (!qos) {
    return X_STATUS_INVALID_PARAMETER;
  }
  kernel_memory()->SystemHeapFree(qos.guest_address());
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetQosRelease, kNetworking, kStub);

dword_result_t NetDll_XNetQosListen_entry(dword_t caller, lpvoid_t id,
                                          lpvoid_t data, dword_t data_size,
                                          dword_t r7, dword_t flags) {
  return X_ERROR_FUNCTION_FAILED;
}
DECLARE_XAM_EXPORT1(NetDll_XNetQosListen, kNetworking, kStub);

dword_result_t NetDll_inet_addr_entry(lpstring_t addr_ptr) {
  if (!addr_ptr) {
    return -1;
  }

  uint32_t addr = inet_addr(addr_ptr);
  // https://docs.microsoft.com/en-us/windows/win32/api/winsock2/nf-winsock2-inet_addr#return-value
  // Based on console research it seems like x360 uses old version of inet_addr
  // In case of empty string it return 0 instead of -1
  if (addr == -1 && !addr_ptr.value().length()) {
    return 0;
  }

  return xe::byte_swap(addr);
}
DECLARE_XAM_EXPORT1(NetDll_inet_addr, kNetworking, kImplemented);

dword_result_t NetDll_socket_entry(dword_t caller, dword_t af, dword_t type,
                                   dword_t protocol) {
  XSocket* socket = new XSocket(kernel_state());
  X_STATUS result = socket->Initialize(XSocket::AddressFamily((uint32_t)af),
                                       XSocket::Type((uint32_t)type),
                                       XSocket::Protocol((uint32_t)protocol));

  if (XFAILED(result)) {
    socket->Release();

    XThread::SetLastError(socket->GetLastWSAError());
    XELOGE("NetDll_socket: failed with error {:08X}",
           socket->GetLastWSAError());
    return -1;
  }

  return socket->handle();
}
DECLARE_XAM_EXPORT1(NetDll_socket, kNetworking, kImplemented);

dword_result_t NetDll_closesocket_entry(dword_t caller, dword_t socket_handle) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  // TODO: Absolutely delete this object. It is no longer valid after calling
  // closesocket.
  socket->Close();
  socket->ReleaseHandle();
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_closesocket, kNetworking, kImplemented);

int_result_t NetDll_shutdown_entry(dword_t caller, dword_t socket_handle,
                                   int_t how) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  auto ret = socket->Shutdown(how);
  if (ret == -1) {
    XThread::SetLastError(socket->GetLastWSAError());
  }
  return ret;
}
DECLARE_XAM_EXPORT1(NetDll_shutdown, kNetworking, kImplemented);

dword_result_t NetDll_setsockopt_entry(dword_t caller, dword_t socket_handle,
                                       dword_t level, dword_t optname,
                                       lpvoid_t optval_ptr, dword_t optlen) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  X_STATUS status = socket->SetOption(level, optname, optval_ptr, optlen);
  return XSUCCEEDED(status) ? 0 : -1;
}
DECLARE_XAM_EXPORT1(NetDll_setsockopt, kNetworking, kImplemented);

dword_result_t NetDll_getsockopt_entry(dword_t caller, dword_t socket_handle,
                                       dword_t level, dword_t optname,
                                       lpvoid_t optval_ptr, lpdword_t optlen) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  uint32_t native_len = *optlen;
  X_STATUS status = socket->GetOption(level, optname, optval_ptr, &native_len);
  return XSUCCEEDED(status) ? 0 : -1;
}
DECLARE_XAM_EXPORT1(NetDll_getsockopt, kNetworking, kImplemented);

dword_result_t NetDll_ioctlsocket_entry(dword_t caller, dword_t socket_handle,
                                        dword_t cmd, lpvoid_t arg_ptr) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  X_STATUS status = socket->IOControl(cmd, arg_ptr);
  if (XFAILED(status)) {
    XThread::SetLastError(socket->GetLastWSAError());
    XELOGE("NetDll_ioctlsocket: failed with error {:08X}",
           socket->GetLastWSAError());
    return -1;
  }

  // TODO
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_ioctlsocket, kNetworking, kImplemented);

dword_result_t NetDll_bind_entry(dword_t caller, dword_t socket_handle,
                                 pointer_t<XSOCKADDR_IN> name,
                                 dword_t namelen) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  N_XSOCKADDR_IN native_name(name);
  X_STATUS status = socket->Bind(&native_name, namelen);
  if (XFAILED(status)) {
    XThread::SetLastError(socket->GetLastWSAError());
    XELOGE("NetDll_bind: failed with error {:08X}", socket->GetLastWSAError());
    return -1;
  }

  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_bind, kNetworking, kImplemented);

dword_result_t NetDll_connect_entry(dword_t caller, dword_t socket_handle,
                                    pointer_t<XSOCKADDR> name,
                                    dword_t namelen) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  N_XSOCKADDR native_name(name);
  X_STATUS status = socket->Connect(&native_name, namelen);
  if (XFAILED(status)) {
    XThread::SetLastError(socket->GetLastWSAError());
    return -1;
  }

  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_connect, kNetworking, kImplemented);

dword_result_t NetDll_listen_entry(dword_t caller, dword_t socket_handle,
                                   int_t backlog) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  X_STATUS status = socket->Listen(backlog);
  if (XFAILED(status)) {
    XThread::SetLastError(socket->GetLastWSAError());
    return -1;
  }

  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_listen, kNetworking, kImplemented);

dword_result_t NetDll_accept_entry(dword_t caller, dword_t socket_handle,
                                   pointer_t<XSOCKADDR> addr_ptr,
                                   lpdword_t addrlen_ptr) {
  if (!addr_ptr) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAEFAULT));
    return -1;
  }

  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  N_XSOCKADDR native_addr(addr_ptr);
  int native_len = *addrlen_ptr;
  auto new_socket = socket->Accept(&native_addr, &native_len);
  if (new_socket) {
    addr_ptr->address_family = native_addr.address_family;
    std::memcpy(addr_ptr->sa_data, native_addr.sa_data, *addrlen_ptr - 2);
    *addrlen_ptr = native_len;

    return new_socket->handle();
  } else {
    return -1;
  }
}
DECLARE_XAM_EXPORT1(NetDll_accept, kNetworking, kImplemented);

struct x_fd_set {
  xe::be<uint32_t> fd_count;
  xe::be<uint32_t> fd_array[64];
};

struct host_set {
  uint32_t count;
  object_ref<XSocket> sockets[64];

  void Load(const x_fd_set* guest_set) {
    assert_true(guest_set->fd_count < 64);
    this->count = guest_set->fd_count;
    for (uint32_t i = 0; i < this->count; ++i) {
      auto socket_handle = static_cast<X_HANDLE>(guest_set->fd_array[i]);
      if (socket_handle == -1) {
        this->count = i;
        break;
      }
      // Convert from Xenia -> native
      auto socket =
          kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
      assert_not_null(socket);
      this->sockets[i] = socket;
    }
  }

  void Store(x_fd_set* guest_set) {
    guest_set->fd_count = 0;
    for (uint32_t i = 0; i < this->count; ++i) {
      auto socket = this->sockets[i];
      guest_set->fd_array[guest_set->fd_count++] = socket->handle();
    }
  }

  void Store(fd_set* native_set) {
    FD_ZERO(native_set);
    for (uint32_t i = 0; i < this->count; ++i) {
      FD_SET(this->sockets[i]->native_handle(), native_set);
    }
  }

  void UpdateFrom(fd_set* native_set) {
    uint32_t new_count = 0;
    for (uint32_t i = 0; i < this->count; ++i) {
      auto socket = this->sockets[i];
      if (FD_ISSET(socket->native_handle(), native_set)) {
        this->sockets[new_count++] = socket;
      }
    }
    this->count = new_count;
  }
};

int_result_t NetDll_select_entry(dword_t caller, dword_t nfds,
                                 pointer_t<x_fd_set> readfds,
                                 pointer_t<x_fd_set> writefds,
                                 pointer_t<x_fd_set> exceptfds,
                                 lpvoid_t timeout_ptr) {
  host_set host_readfds = {0};
  fd_set native_readfds = {0};
  if (readfds) {
    host_readfds.Load(readfds);
    host_readfds.Store(&native_readfds);
  }
  host_set host_writefds = {0};
  fd_set native_writefds = {0};
  if (writefds) {
    host_writefds.Load(writefds);
    host_writefds.Store(&native_writefds);
  }
  host_set host_exceptfds = {0};
  fd_set native_exceptfds = {0};
  if (exceptfds) {
    host_exceptfds.Load(exceptfds);
    host_exceptfds.Store(&native_exceptfds);
  }
  timeval* timeout_in = nullptr;
  timeval timeout;
  if (timeout_ptr) {
    timeout = {static_cast<int32_t>(timeout_ptr.as_array<int32_t>()[0]),
               static_cast<int32_t>(timeout_ptr.as_array<int32_t>()[1])};
    Clock::ScaleGuestDurationTimeval(
        reinterpret_cast<int32_t*>(&timeout.tv_sec),
        reinterpret_cast<int32_t*>(&timeout.tv_usec));
    timeout_in = &timeout;
  }
  int ret = select(nfds, readfds ? &native_readfds : nullptr,
                   writefds ? &native_writefds : nullptr,
                   exceptfds ? &native_exceptfds : nullptr, timeout_in);
  if (readfds) {
    host_readfds.UpdateFrom(&native_readfds);
    host_readfds.Store(readfds);
  }
  if (writefds) {
    host_writefds.UpdateFrom(&native_writefds);
    host_writefds.Store(writefds);
  }
  if (exceptfds) {
    host_exceptfds.UpdateFrom(&native_exceptfds);
    host_exceptfds.Store(exceptfds);
  }

  // TODO(gibbed): modify ret to be what's actually copied to the guest fd_sets?
  return ret;
}
DECLARE_XAM_EXPORT1(NetDll_select, kNetworking, kImplemented);

dword_result_t NetDll_recv_entry(dword_t caller, dword_t socket_handle,
                                 lpvoid_t buf_ptr, dword_t buf_len,
                                 dword_t flags) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  return socket->Recv(buf_ptr, buf_len, flags);
}
DECLARE_XAM_EXPORT1(NetDll_recv, kNetworking, kImplemented);

dword_result_t NetDll_recvfrom_entry(dword_t caller, dword_t socket_handle,
                                     lpvoid_t buf_ptr, dword_t buf_len,
                                     dword_t flags,
                                     pointer_t<XSOCKADDR_IN> from_ptr,
                                     lpdword_t fromlen_ptr) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  N_XSOCKADDR_IN native_from;
  if (from_ptr) {
    native_from = *from_ptr;
  }
  uint32_t native_fromlen = fromlen_ptr ? fromlen_ptr.value() : 0;
  int ret = socket->RecvFrom(buf_ptr, buf_len, flags, &native_from,
                             fromlen_ptr ? &native_fromlen : 0);

  if (from_ptr) {
    from_ptr->sin_family = native_from.sin_family;
    from_ptr->sin_port = native_from.sin_port;
    from_ptr->sin_addr = native_from.sin_addr;
    std::memset(from_ptr->x_sin_zero, 0, sizeof(from_ptr->x_sin_zero));
  }
  if (fromlen_ptr) {
    *fromlen_ptr = native_fromlen;
  }

  if (ret == -1) {
    XThread::SetLastError(socket->GetLastWSAError());
  }

  return ret;
}
DECLARE_XAM_EXPORT1(NetDll_recvfrom, kNetworking, kImplemented);

dword_result_t NetDll_send_entry(dword_t caller, dword_t socket_handle,
                                 lpvoid_t buf_ptr, dword_t buf_len,
                                 dword_t flags) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  return socket->Send(buf_ptr, buf_len, flags);
}
DECLARE_XAM_EXPORT1(NetDll_send, kNetworking, kImplemented);

dword_result_t NetDll_sendto_entry(dword_t caller, dword_t socket_handle,
                                   lpvoid_t buf_ptr, dword_t buf_len,
                                   dword_t flags,
                                   pointer_t<XSOCKADDR_IN> to_ptr,
                                   dword_t to_len) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  N_XSOCKADDR_IN native_to(to_ptr);
  int ret = socket->SendTo(buf_ptr, buf_len, flags, &native_to, to_len);
  return ret;
}
DECLARE_XAM_EXPORT1(NetDll_sendto, kNetworking, kImplemented);

dword_result_t NetDll___WSAFDIsSet_entry(dword_t socket_handle,
                                         pointer_t<x_fd_set> fd_set) {
  const uint8_t max_fd_count =
      std::min((uint32_t)fd_set->fd_count, uint32_t(64));
  for (uint8_t i = 0; i < max_fd_count; i++) {
    if (fd_set->fd_array[i] == socket_handle) {
      return 1;
    }
  }
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll___WSAFDIsSet, kNetworking, kImplemented);

void NetDll_WSASetLastError_entry(dword_t error_code) {
  XThread::SetLastError(error_code);
}
DECLARE_XAM_EXPORT1(NetDll_WSASetLastError, kNetworking, kImplemented);

dword_result_t NetDll_getsockname_entry(dword_t caller, dword_t socket_handle,
                                        lpvoid_t buf_ptr, lpdword_t len_ptr) {
  auto socket =
      kernel_state()->object_table()->LookupObject<XSocket>(socket_handle);
  if (!socket) {
    XThread::SetLastError(uint32_t(X_WSAError::X_WSAENOTSOCK));
    return -1;
  }

  int buffer_len = *len_ptr;

  X_STATUS status = socket->GetSockName(buf_ptr, &buffer_len);
  if (XFAILED(status)) {
    XThread::SetLastError(socket->GetLastWSAError());
    return -1;
  }

  *len_ptr = buffer_len;
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_getsockname, kNetworking, kImplemented);

dword_result_t NetDll_XNetCreateKey_entry(dword_t caller, lpdword_t key_id,
                                          lpdword_t exchange_key) {
  kernel_memory()->Fill(key_id.guest_address(), 8, 0xBE);
  kernel_memory()->Fill(exchange_key.guest_address(), 16, 0xBE);
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetCreateKey, kNetworking, kStub);

dword_result_t NetDll_XNetRegisterKey_entry(dword_t caller, lpdword_t key_id,
                                            lpdword_t exchange_key) {
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetRegisterKey, kNetworking, kStub);

dword_result_t NetDll_XNetUnregisterKey_entry(dword_t caller,
                                              lpdword_t key_id) {
  return 0;
}
DECLARE_XAM_EXPORT1(NetDll_XNetUnregisterKey, kNetworking, kStub);

// [NETWORKING STUBS] These NetDll_* and XNet* functions require a full
// Xbox LIVE networking stack. Without it, return appropriate error codes.

// NetDll_XNetServerToInAddr (ordinal 0x3A)
dword_result_t NetDll_XNetServerToInAddr_entry(
    dword_t user_index, qword_t xuid, dword_t service_id,
    lpdword_t in_addr_out) {
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_XNetServerToInAddr, kNetworking, kStub);

// NetDll_XNetUnregisterInAddr (ordinal 0x3F)
dword_result_t NetDll_XNetUnregisterInAddr_entry(dword_t in_addr) {
  return X_ERROR_SUCCESS;
}
DECLARE_XAM_EXPORT1(NetDll_XNetUnregisterInAddr, kNetworking, kStub);

// NetDll_XNetConnect (ordinal 0x41)
dword_result_t NetDll_XNetConnect_entry(dword_t in_addr) {
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_XNetConnect, kNetworking, kStub);

// NetDll_XNetGetConnectStatus (ordinal 0x42)
dword_result_t NetDll_XNetGetConnectStatus_entry(dword_t in_addr) {
  return 0;  // XNET_CONNECT_STATUS_IDLE
}
DECLARE_XAM_EXPORT1(NetDll_XNetGetConnectStatus, kNetworking, kStub);

// NetDll_XNetQosLookup (ordinal 0x46)
dword_result_t NetDll_XNetQosLookup_entry(
    dword_t user_index, lpvoid_t in_addrs, dword_t in_addr_count,
    lpvoid_t out_buf, dword_t out_buf_size) {
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_XNetQosLookup, kNetworking, kStub);

// NetDll_XnpGetConfigStatus (ordinal 0x4xx)
dword_result_t NetDll_XnpGetConfigStatus_entry(lpdword_t status_out) {
  if (status_out) {
    *status_out = 0;
  }
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_XnpGetConfigStatus, kNetworking, kStub);

// NetDll_getpeername (ordinal 0x0A)
dword_result_t NetDll_getpeername_entry(dword_t socket,
                                         pointer_t<uint8_t> name,
                                         lpdword_t namelen) {
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_getpeername, kNetworking, kStub);

// NetDll_WSAGetOverlappedResult (ordinal 0x10)
dword_result_t NetDll_WSAGetOverlappedResult_entry(
    dword_t socket, pointer_t<uint8_t> overlapped,
    lpdword_t bytes_transferred, dword_t wait, lpdword_t flags) {
  if (bytes_transferred) {
    *bytes_transferred = 0;
  }
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_WSAGetOverlappedResult, kNetworking, kStub);

// NetDll_WSAEventSelect (ordinal 0x4xx)
dword_result_t NetDll_WSAEventSelect_entry(dword_t socket, dword_t event,
                                            dword_t network_events) {
  return 0;  // success
}
DECLARE_XAM_EXPORT1(NetDll_WSAEventSelect, kNetworking, kStub);

// NetDll_WSACancelOverlappedIO (ordinal 0x4xx)
dword_result_t NetDll_WSACancelOverlappedIO_entry(dword_t socket) {
  return 0;  // success
}
DECLARE_XAM_EXPORT1(NetDll_WSACancelOverlappedIO, kNetworking, kStub);

// NetDll_WSARecv (ordinal 0x4xx)
dword_result_t NetDll_WSARecv_entry(
    dword_t socket, pointer_t<uint8_t> buffers, dword_t buffer_count,
    lpdword_t bytes_received, lpdword_t flags,
    pointer_t<uint8_t> overlapped, lpvoid_t completion_routine) {
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_WSARecv, kNetworking, kStub);

// NetDll_WSASend (ordinal 0x4xx)
dword_result_t NetDll_WSASend_entry(
    dword_t socket, pointer_t<uint8_t> buffers, dword_t buffer_count,
    lpdword_t bytes_sent, dword_t flags,
    pointer_t<uint8_t> overlapped, lpvoid_t completion_routine) {
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(NetDll_WSASend, kNetworking, kStub);

// XNetLogonGetMachineID (ordinal 0x135)
dword_result_t XNetLogonGetMachineID_entry(pointer_t<uint8_t> machine_id_out) {
  if (machine_id_out) {
    std::memset(machine_id_out, 0, 0x14);  // XNET_MACHINE_ID is ~20 bytes
  }
  return 0x80151802;
}
DECLARE_XAM_EXPORT1(XNetLogonGetMachineID, kNetworking, kStub);

// XNetLogonGetTitleID (ordinal 0x136)
dword_result_t XNetLogonGetTitleID_entry(lpdword_t title_id_out) {
  if (title_id_out) {
    *title_id_out = kernel_state()->title_id();
  }
  return X_ERROR_SUCCESS;
}
DECLARE_XAM_EXPORT1(XNetLogonGetTitleID, kNetworking, kStub);

// =============================================================================
// NetDll_XHttp* stubs (ordinals 0xC9 - 0xE0)
//
// ax360e real-fix for ROTTR (Rise of the Tomb Raider) black screen.
//
// ROOT CAUSE:
//   Rise of the Tomb Raider (Crystal Dynamics engine, title ID 53510823)
//   statically links XHTTP (Xbox HTTP) via NetDll_XHttp*. On real Xbox 360
//   hardware, NetDll_XHttp* is provided by the network stack. Xenia-canary
//   upstream has NEVER implemented these (confirmed via GitHub search of
//   upstream xenia-canary repo - 0 PRs, 0 issues, 0 code matches for
//   "XHttpDoWork"). Without an implementation, every call hits
//   a64_emitter.cc:UndefinedCallExtern which XELOGE's "undefined extern call
//   to ..." and returns 0.
//
//   ROTTR's main thread calls NetDll_XHttpDoWork() in a tight loop (typical
//   pattern: `while (NetDll_XHttpDoWork()) {}` to drain pending HTTP work).
//   Because UndefinedCallExtern returns 0, the loop NEVER exits and the
//   main XThread never returns control to the renderer. The GPU Commands
//   thread (01000010) never receives a frame kickoff, and the user sees
//   a black screen. The log floods with 5423+ "undefined extern call to
//   82D0CCCC NetDll_XHttpDoWork" lines.
//
// FIX:
//   Provide stubs for all 24 NetDll_XHttp* functions (ordinals 0xC9-0xE0).
//   The stubs model "network offline / no HTTP work to do":
//
//     - Startup/Shutdown/Option/SetOption/SetCredentials: return success (TRUE
//       or 0) so the game initializes its HTTP machinery without erroring out.
//     - Open/OpenRequest: return a non-zero pseudo-handle (0xDEADBEEF) so the
//       game treats it as a valid HINTERNET. The game then tries to send a
//       request, which fails (see SendRequest/ReceiveResponse), and finally
//       closes the handle via CloseHandle (no-op).
//     - DoWork: return FALSE (0) - this is the KEY fix. Returning FALSE tells
//       the caller there is no pending HTTP work, so the `while (DoWork()){}`
//       loop exits immediately and the main thread can proceed to rendering.
//     - SendRequest/ReceiveResponse/QueryHeaders/ReadData/WriteData: return
//       FALSE with GetLastError = ERROR_WINHTTP_CANNOT_CONNECT (0x00002EEF)
//       or ERROR_WINHTTP_TIMEOUT (0x00002EE2), so the game treats the request
//       as failed and falls through to its "no network" code path.
//     - CrackUrl/CreateUrl/QueryAuthSchemes/QueryOption/GetPerfCounters/
//       ResetPerfCounters: return appropriate success/empty values.
//
// These stubs are also appropriate for any other title that uses XHTTP for
// online services (achievements, leaderboards, telemetry) while being
// primarily single-player. They mirror the semantics of the existing NetDll_*
// stubs above (returning 0x80151802 / X_ERROR_NOT_FOUND for "not connected").
//
// References:
//   - https://github.com/xenia-canary/xenia-canary/issues/754 (aggregate
//     "Unimplemented Kernel Functions" issue - NetDll_XHttp* explicitly
//     listed as unimplemented)
//   - WinHTTP error codes:
//     ERROR_WINHTTP_CANNOT_CONNECT = 12029 (0x2EED)
//     ERROR_WINHTTP_TIMEOUT        = 12002 (0x2EE2)
//     ERROR_WINHTTP_OPERATION_CANCELLED = 12017 (0x2EF1)
// =============================================================================

// NetDll_XHttpStartup (ordinal 0xC9)
dword_result_t NetDll_XHttpStartup_entry() {
  // TRUE = success. The Xbox 360 XHttpStartup returns BOOL.
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_XHttpStartup, kNetworking, kStub);

// NetDll_XHttpShutdown (ordinal 0xCA)
dword_result_t NetDll_XHttpShutdown_entry() {
  // BOOL success.
  return 1;
}
DECLARE_XAM_EXPORT1(NetDll_XHttpShutdown, kNetworking, kStub);

// NetDll_XHttpOpen (ordinal 0xCB)
//   HINTERNET XHttpOpen(LPCWSTR agent, DWORD access_type, LPCWSTR proxy,
//                       LPCWSTR bypass, DWORD flags)
// Returns a non-zero pseudo-handle so the game proceeds. The handle will fail
// on the first actual network operation (SendRequest/ReceiveResponse).
dword_result_t NetDll_XHttpOpen_entry(lpvoid_t agent, dword_t access_type,
                                       lpvoid_t proxy, lpvoid_t bypass,
                                       dword_t flags) {
  return 0xDEADBEEF;  // pseudo HINTERNET
}
DECLARE_XAM_EXPORT1(NetDll_XHttpOpen, kNetworking, kStub);

// NetDll_XHttpCloseHandle (ordinal 0xCC)
//   BOOL XHttpCloseHandle(HINTERNET)
dword_result_t NetDll_XHttpCloseHandle_entry(dword_t handle) {
  return 1;  // TRUE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpCloseHandle, kNetworking, kStub);

// NetDll_XHttpConnect (ordinal 0xCD)
//   HINTERNET XHttpConnect(HINTERNET, LPCWSTR server, INTERNET_PORT, DWORD)
dword_result_t NetDll_XHttpConnect_entry(dword_t session, lpvoid_t server,
                                          dword_t port, dword_t flags) {
  return 0xDEADBEEF;  // pseudo connect handle
}
DECLARE_XAM_EXPORT1(NetDll_XHttpConnect, kNetworking, kStub);

// NetDll_XHttpSetStatusCallback (ordinal 0xCE)
//   LPVOID XHttpSetStatusCallback(HINTERNET, LPVOID callback, DWORD, DWORD_PTR)
// Returns the previous callback (NULL on first call).
dword_result_t NetDll_XHttpSetStatusCallback_entry(dword_t handle,
                                                    lpvoid_t callback,
                                                    dword_t flags,
                                                    qword_t reserved) {
  return 0;  // NULL = no previous callback
}
DECLARE_XAM_EXPORT1(NetDll_XHttpSetStatusCallback, kNetworking, kStub);

// NetDll_XHttpOpenRequest (ordinal 0xCF)
//   HINTERNET XHttpOpenRequest(HINTERNET connect, LPCWSTR verb,
//                              LPCWSTR path, LPCWSTR version,
//                              LPCWSTR referrer, LPCWSTR* accept_types,
//                              DWORD flags)
dword_result_t NetDll_XHttpOpenRequest_entry(
    dword_t connect, lpvoid_t verb, lpvoid_t path, lpvoid_t version,
    lpvoid_t referrer, lpvoid_t accept_types, dword_t flags) {
  return 0xDEADBEEF;  // pseudo request handle
}
DECLARE_XAM_EXPORT1(NetDll_XHttpOpenRequest, kNetworking, kStub);

// NetDll_XHttpOpenRequestUsingMemory (ordinal 0xD0)
// Same as XHttpOpenRequest but path is in-memory. Same stub semantics.
dword_result_t NetDll_XHttpOpenRequestUsingMemory_entry(
    dword_t connect, lpvoid_t verb, lpvoid_t path, dword_t path_size,
    lpvoid_t version, lpvoid_t referrer, lpvoid_t accept_types, dword_t flags) {
  return 0xDEADBEEF;
}
DECLARE_XAM_EXPORT1(NetDll_XHttpOpenRequestUsingMemory, kNetworking, kStub);

// NetDll_XHttpSendRequest (ordinal 0xD1)
//   BOOL XHttpSendRequest(HINTERNET, LPCWSTR headers, DWORD headers_len,
//                         LPVOID optional, DWORD optional_len, DWORD total_len,
//                         qword_t context)
// Returns FALSE with GetLastError = ERROR_WINHTTP_CANNOT_CONNECT so the game
// gives up on the request and proceeds to its "no network" code path.
dword_result_t NetDll_XHttpSendRequest_entry(
    dword_t request, lpvoid_t headers, dword_t headers_len,
    lpvoid_t optional, dword_t optional_len, dword_t total_len,
    qword_t context) {
  XThread::SetLastError(0x00002EED);  // ERROR_WINHTTP_CANNOT_CONNECT
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpSendRequest, kNetworking, kStub);

// NetDll_XHttpReceiveResponse (ordinal 0xD2)
//   BOOL XHttpReceiveResponse(HINTERNET, LPVOID overlapped)
dword_result_t NetDll_XHttpReceiveResponse_entry(dword_t request,
                                                  lpvoid_t overlapped) {
  XThread::SetLastError(0x00002EEF);  // ERROR_WINHTTP_OPERATION_CANCELLED
  return 0;  // FALSE - no response
}
DECLARE_XAM_EXPORT1(NetDll_XHttpReceiveResponse, kNetworking, kStub);

// NetDll_XHttpQueryHeaders (ordinal 0xD3)
//   BOOL XHttpQueryHeaders(HINTERNET, DWORD info_level, LPCWSTR name,
//                          LPVOID buffer, LPDWORD buffer_len, LPDWORD index)
dword_result_t NetDll_XHttpQueryHeaders_entry(
    dword_t request, dword_t info_level, lpvoid_t name, lpvoid_t buffer,
    lpdword_t buffer_len, lpdword_t index) {
  if (buffer_len) {
    *buffer_len = 0;
  }
  XThread::SetLastError(0x00002EE2);  // ERROR_WINHTTP_HEADER_NOT_FOUND
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpQueryHeaders, kNetworking, kStub);

// NetDll_XHttpReadData (ordinal 0xD4)
//   BOOL XHttpReadData(HINTERNET, LPVOID buffer, DWORD bytes_to_read,
//                      LPDWORD bytes_read, LPVOID overlapped)
dword_result_t NetDll_XHttpReadData_entry(dword_t request, lpvoid_t buffer,
                                           dword_t bytes_to_read,
                                           lpdword_t bytes_read,
                                           lpvoid_t overlapped) {
  if (bytes_read) {
    *bytes_read = 0;
  }
  XThread::SetLastError(0x00002EEF);  // ERROR_WINHTTP_OPERATION_CANCELLED
  return 0;  // FALSE - no data
}
DECLARE_XAM_EXPORT1(NetDll_XHttpReadData, kNetworking, kStub);

// NetDll_XHttpWriteData (ordinal 0xD5)
//   BOOL XHttpWriteData(HINTERNET, LPCVOID buffer, DWORD bytes_to_write,
//                       LPDWORD bytes_written, LPVOID overlapped)
dword_result_t NetDll_XHttpWriteData_entry(dword_t request, lpvoid_t buffer,
                                            dword_t bytes_to_write,
                                            lpdword_t bytes_written,
                                            lpvoid_t overlapped) {
  if (bytes_written) {
    *bytes_written = 0;
  }
  XThread::SetLastError(0x00002EEF);  // ERROR_WINHTTP_OPERATION_CANCELLED
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpWriteData, kNetworking, kStub);

// NetDll_XHttpQueryOption (ordinal 0xD6)
//   BOOL XHttpQueryOption(HINTERNET, DWORD option, LPVOID buffer,
//                         LPDWORD buffer_len)
dword_result_t NetDll_XHttpQueryOption_entry(dword_t handle, dword_t option,
                                              lpvoid_t buffer,
                                              lpdword_t buffer_len) {
  if (buffer_len) {
    *buffer_len = 0;
  }
  XThread::SetLastError(0x00002EE2);  // ERROR_WINHTTP_INVALID_OPTION
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpQueryOption, kNetworking, kStub);

// NetDll_XHttpSetOption (ordinal 0xD7)
//   BOOL XHttpSetOption(HINTERNET, DWORD option, LPVOID buffer, DWORD len)
dword_result_t NetDll_XHttpSetOption_entry(dword_t handle, dword_t option,
                                            lpvoid_t buffer, dword_t len) {
  return 1;  // TRUE - accept whatever option the game wants to set
}
DECLARE_XAM_EXPORT1(NetDll_XHttpSetOption, kNetworking, kStub);

// NetDll_XHttpDoWork (ordinal 0xD8) - THE KEY FIX
//   DWORD XHttpDoWork()
//
// On real Xbox 360, XHttpDoWork drains pending HTTP request work and returns
// TRUE if there is more work to do, FALSE if the queue is empty. Games
// typically call it in a `while (XHttpDoWork()) {}` loop until the queue is
// drained.
//
// Returning FALSE (0) here tells the caller the queue is empty, so the loop
// exits immediately. This is what unblocks ROTTR's main thread from the
// busy-loop it was stuck in (5423+ "undefined extern call" log lines, no
// GPU frame ever kicked off).
dword_result_t NetDll_XHttpDoWork_entry() {
  return 0;  // FALSE - no pending HTTP work, queue empty
}
DECLARE_XAM_EXPORT1(NetDll_XHttpDoWork, kNetworking, kStub);

// NetDll_XHttpSetCredentials (ordinal 0xD9)
//   BOOL XHttpSetCredentials(HINTERNET, DWORD auth_target, DWORD auth_scheme,
//                            LPCWSTR username, LPCWSTR password, LPVOID)
dword_result_t NetDll_XHttpSetCredentials_entry(
    dword_t handle, dword_t auth_target, dword_t auth_scheme,
    lpvoid_t username, lpvoid_t password, lpvoid_t reserved) {
  return 1;  // TRUE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpSetCredentials, kNetworking, kStub);

// NetDll_XHttpQueryAuthSchemes (ordinal 0xDA)
//   BOOL XHttpQueryAuthSchemes(HINTERNET, DWORD supported_schemes,
//                              DWORD first_scheme, DWORD selected_scheme)
dword_result_t NetDll_XHttpQueryAuthSchemes_entry(
    dword_t request, dword_t supported_schemes, dword_t first_scheme,
    lpdword_t selected_scheme_out) {
  if (selected_scheme_out) {
    *selected_scheme_out = 0;  // no auth scheme selected
  }
  return 1;  // TRUE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpQueryAuthSchemes, kNetworking, kStub);

// NetDll_XHttpCrackUrlW (ordinal 0xDB) - wide-string variant
//   BOOL XHttpCrackUrlW(LPCWSTR url, DWORD url_len, DWORD flags,
//                       LPURL_COMPONENTSW components)
dword_result_t NetDll_XHttpCrackUrlW_entry(lpvoid_t url, dword_t url_len,
                                            dword_t flags, lpvoid_t components) {
  XThread::SetLastError(0x00002EE9);  // ERROR_WINHTTP_UNRECOGNIZED_SCHEME
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpCrackUrlW, kNetworking, kStub);

// NetDll_XHttpCrackUrl (ordinal 0xDC) - ANSI variant
dword_result_t NetDll_XHttpCrackUrl_entry(lpvoid_t url, dword_t url_len,
                                           dword_t flags, lpvoid_t components) {
  XThread::SetLastError(0x00002EE9);  // ERROR_WINHTTP_UNRECOGNIZED_SCHEME
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpCrackUrl, kNetworking, kStub);

// NetDll_XHttpCreateUrl (ordinal 0xDD) - ANSI
//   BOOL XHttpCreateUrl(LPURL_COMPONENTS components, DWORD flags,
//                       LPSTR url, LPDWORD url_len)
dword_result_t NetDll_XHttpCreateUrl_entry(lpvoid_t components, dword_t flags,
                                            lpvoid_t url, lpdword_t url_len) {
  if (url_len) {
    *url_len = 0;
  }
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpCreateUrl, kNetworking, kStub);

// NetDll_XHttpCreateUrlW (ordinal 0xDE) - wide
dword_result_t NetDll_XHttpCreateUrlW_entry(lpvoid_t components, dword_t flags,
                                             lpvoid_t url, lpdword_t url_len) {
  if (url_len) {
    *url_len = 0;
  }
  return 0;  // FALSE
}
DECLARE_XAM_EXPORT1(NetDll_XHttpCreateUrlW, kNetworking, kStub);

// NetDll_XHttpResetPerfCounters (ordinal 0xDF)
//   void XHttpResetPerfCounters()
void NetDll_XHttpResetPerfCounters_entry() {
  // no-op
}
DECLARE_XAM_EXPORT1(NetDll_XHttpResetPerfCounters, kNetworking, kStub);

// NetDll_XHttpGetPerfCounters (ordinal 0xE0)
//   void XHttpGetPerfCounters(LPHTTP_PERF_COUNTERS counters)
void NetDll_XHttpGetPerfCounters_entry(lpvoid_t counters) {
  // Zero out the perf counters struct so the game doesn't read garbage.
  if (counters) {
    std::memset(counters, 0, 0x40);  // XHTTP_PERF_COUNTERS is ~64 bytes
  }
}
DECLARE_XAM_EXPORT1(NetDll_XHttpGetPerfCounters, kNetworking, kStub);

}  // namespace xam
}  // namespace kernel
}  // namespace xe

DECLARE_XAM_EMPTY_REGISTER_EXPORTS(Net);
