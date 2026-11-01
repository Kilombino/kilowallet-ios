//! Live check on a desktop (not shipped): start Tor through the C functions, wait for it, reach
//! onion services through its SOCKS port, stop it and start it again on the same port.
//!   cargo run --example live -- <dir> <port> host.onion:port [more...]
use std::ffi::{CStr, CString};
use std::io::{Read, Write};
use std::net::TcpStream;
use std::time::{Duration, Instant};

fn start(dir: &str, port: u16) {
    let d = CString::new(dir).unwrap();
    let l = CString::new(format!("{dir}/tor.log")).unwrap();
    let r = unsafe { kilombino_tor::kilombino_tor_start(d.as_ptr(), l.as_ptr(), port) };
    if !r.is_null() {
        let why = unsafe { CStr::from_ptr(r) }.to_string_lossy().into_owned();
        unsafe { kilombino_tor::kilombino_tor_free(r) };
        panic!("start: {why}");
    }
}

fn wait_ready(secs: u64) -> Duration {
    let t = Instant::now();
    while kilombino_tor::kilombino_tor_state() != 2 {
        assert!(kilombino_tor::kilombino_tor_state() != 0, "Tor stopped");
        assert!(t.elapsed() < Duration::from_secs(secs), "not bootstrapped in {secs} s");
        std::thread::sleep(Duration::from_millis(200));
    }
    t.elapsed()
}

/// SOCKS5 CONNECT with the name resolved by Tor; Ok when the far end accepted.
fn socks(port: u16, target: &str) -> Result<(), String> {
    let (host, p) = target.rsplit_once(':').unwrap();
    let p: u16 = p.parse().unwrap();
    let mut s = TcpStream::connect(("127.0.0.1", port)).map_err(|e| e.to_string())?;
    s.set_read_timeout(Some(Duration::from_secs(120))).unwrap();
    s.write_all(&[5, 1, 0]).unwrap();
    let mut m = [0u8; 2];
    s.read_exact(&mut m).map_err(|e| e.to_string())?;
    let mut req = vec![5, 1, 0, 3, host.len() as u8];
    req.extend_from_slice(host.as_bytes());
    req.extend_from_slice(&p.to_be_bytes());
    s.write_all(&req).unwrap();
    let mut r = [0u8; 10];
    s.read_exact(&mut r).map_err(|e| e.to_string())?;
    if r[1] == 0 { Ok(()) } else { Err(format!("SOCKS reply {}", r[1])) }
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let (dir, port) = (&a[1], a[2].parse::<u16>().unwrap());
    start(dir, port);
    println!("bootstrapped in {:?}", wait_ready(300));
    for t in &a[3..] {
        let t0 = Instant::now();
        println!("{t}: {:?} in {:?}", socks(port, t), t0.elapsed());
    }
    kilombino_tor::kilombino_tor_stop();
    std::thread::sleep(Duration::from_secs(1));
    start(dir, port);
    println!("again: bootstrapped in {:?}", wait_ready(300));
    if let Some(t) = a.get(3) { println!("again {t}: {:?}", socks(port, t)); }
    kilombino_tor::kilombino_tor_stop();
    std::thread::sleep(Duration::from_secs(1));
    println!("state after stop: {}", kilombino_tor::kilombino_tor_state());
}
