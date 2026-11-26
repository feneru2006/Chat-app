# In ra danh sách IPv4 của máy dạng: ip:192.168.1.5,ip:10.0.0.3 (dùng cho gen-cert.bat)
try {
  (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -notlike '169.254*' -and $_.IPAddress -ne '127.0.0.1' } |
    ForEach-Object { 'ip:' + $_.IPAddress }) -join ','
} catch { }
