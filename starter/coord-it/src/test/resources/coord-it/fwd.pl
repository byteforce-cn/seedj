#!/usr/bin/env perl
# ==============================================================================
# 最小 TCP 转发器（集成测试专用；coord 镜像内置 perl，无需额外安装）
#
# 背景：coord dev/容器化场景下进程只能监听 127.0.0.1（fail-closed），而
# Docker 端口映射（Colima/gvisor-tap-vsock、Linux iptables DNAT）转发目标是
# 容器 IP 而非容器 loopback，导致映射端口不可达。本转发器在同一网络命名空间
# 内监听 0.0.0.0:<port>，把流量回环到 127.0.0.1:<port>。
#
# 用法: fwd.pl LISTEN_HOST:PORT TARGET_HOST:PORT
# ==============================================================================
use strict;
use warnings;
use IO::Socket::INET;
use IO::Select;

my ($listen, $target) = @ARGV;
die "usage: $0 LISTEN_HOST:PORT TARGET_HOST:PORT\n" unless defined $target;
my ($lh, $lp) = split /:/, $listen;
my ($th, $tp) = split /:/, $target;

$SIG{CHLD} = 'IGNORE';    # 自动回收子进程，避免僵尸

my $srv = IO::Socket::INET->new(
    LocalAddr => $lh,
    LocalPort => $lp,
    Proto     => 'tcp',
    Listen    => 64,
    ReuseAddr => 1,
) or die "listen ${listen}: $!\n";

while (1) {
    my $client = $srv->accept;
    next unless $client;

    my $pid = fork();
    next unless defined $pid;
    next if $pid != 0;    # 父进程继续 accept

    # 子进程：承载单个连接的进/出双向泵
    $srv->close;
    my $up = IO::Socket::INET->new(
        PeerAddr => $th,
        PeerPort => $tp,
        Proto    => 'tcp',
    );
    unless ($up) { $client->close; exit 0; }

    my $sel = IO::Select->new($client, $up);
    my %peer = (
        $client->fileno => $up,
        $up->fileno     => $client,
    );

    my $done = 0;
    while (!$done) {
        my @ready = $sel->can_read;
        last unless @ready;
        for my $s (@ready) {
            my $n = sysread($s, my $buf, 65536);
            if (!defined($n) || $n == 0) { $done = 1; last; }
            my $dst = $peer{$s->fileno};
            my $off = 0;
            while ($off < $n) {
                my $wrote = syswrite($dst, $buf, $n - $off, $off);
                if (!defined($wrote) || $wrote == 0) { $done = 1; last; }
                $off += $wrote;
            }
        }
    }
    $client->close;
    $up->close;
    exit 0;
}
