#!/usr/bin/env python3
import argparse
import logging
import os
import signal
import threading
import time
from datetime import datetime
from queue import Queue, Empty

import dateutil.parser
import requests


class CAdvisorCollector:
    def __init__(self, cadvisor_address, output_path, interval_seconds):
        """
        cadvisor_address: hostname o URL di cAdvisor, ad esempio:
          - 'localhost'
          - 'localhost:8080'
          - 'http://localhost:8081'
        output_path: directory in cui salvare i file CSV
        interval_seconds: intervallo in secondi tra una raccolta e la successiva
        """
        self.cadvisor_address = cadvisor_address
        self.output_path = output_path
        self.interval_seconds = int(interval_seconds)

        self.stop_event = threading.Event()
        self.write_queue = Queue()
        self.writer_thread = None
        self.worker_thread = None

        # Per evitare duplicati tra campioni già visti
        self.seen_samples = set()

    def get_cadvisor_base_url(self):
        host_value = self.cadvisor_address
        if not host_value:
            raise ValueError("cAdvisor address not configured")

        host_value = host_value.strip()

        if host_value.startswith("http://") or host_value.startswith("https://"):
            base = host_value
        else:
            base = f"http://{host_value}"

        # Se non è già specificata una porta, usa 8080
        if ":" not in base.split("//", 1)[-1]:
            base = f"{base}:8080"

        return base.rstrip("/")

    def extract_filesystem_counters(self, sample):
        filesystem_list = sample.get("filesystem") or sample.get("fs") or []
        total_reads = total_writes = total_read_bytes = total_write_bytes = 0
        for fs_entry in filesystem_list:
            reads_value = fs_entry.get("reads") or fs_entry.get("readsCompleted")
            writes_value = fs_entry.get("writes") or fs_entry.get("writesCompleted")
            read_bytes_value = (
                fs_entry.get("readBytes")
                or fs_entry.get("readbytes")
                or fs_entry.get("read_bytes")
            )
            write_bytes_value = (
                fs_entry.get("writeBytes")
                or fs_entry.get("writebytes")
                or fs_entry.get("write_bytes")
            )
            try:
                total_reads += int(reads_value or 0)
                total_writes += int(writes_value or 0)
                total_read_bytes += int(read_bytes_value or 0)
                total_write_bytes += int(write_bytes_value or 0)
            except Exception:
                continue
        return total_reads, total_writes, total_read_bytes, total_write_bytes

    def collect_once(self):
        """
        Esegue una singola raccolta da /api/v1.3/docker/ e
        mette le righe pronte nella write_queue.
        """
        if self.stop_event.is_set():
            return

        collection_timestamp = round(datetime.timestamp(datetime.utcnow()))
        base = self.get_cadvisor_base_url()
        url = f"{base}/api/v1.3/docker/"
        response = requests.get(url, timeout=10)
        response.raise_for_status()
        docker_data = response.json()

        # docker_data è un dict: {container_id: {...}, ...}
        for container_id, container_entry in docker_data.items():
            aliases = container_entry.get("aliases") or []
            service_name = aliases[0] if aliases else container_entry.get("name", "").split("/")[-1]
            stats_list = container_entry.get("stats", [])
            image_name = container_entry.get("spec", {}).get("image", "")

            for s in stats_list:
                ts = s.get("timestamp")
                if not ts:
                    continue
                try:
                    sample_ts = round(datetime.timestamp(dateutil.parser.isoparse(ts)))
                except Exception:
                    continue

                unique_key = (container_id, sample_ts)
                if unique_key in self.seen_samples:
                    continue
                self.seen_samples.add(unique_key)

                # Metriche principali
                cpu_total = float(s.get("cpu", {}).get("usage", {}).get("total", 0.0))
                mem_usage = int(s.get("memory", {}).get("usage", 0))
                mem_limit = int(s.get("memory", {}).get("limit", 0))

                # I/O filesystem
                reads_count, writes_count, read_bytes, write_bytes = self.extract_filesystem_counters(s)

                # I/O rete
                net = s.get("network", {}) or {}
                rx_bytes = int(net.get("rx_bytes", 0))
                tx_bytes = int(net.get("tx_bytes", 0))
                rx_packets = int(net.get("rx_packets", 0))
                tx_packets = int(net.get("tx_packets", 0))

                line = (
                    f"{collection_timestamp},{sample_ts},{service_name},{image_name},"
                    f"{cpu_total},{mem_usage},{mem_limit},{reads_count},{writes_count},"
                    f"{read_bytes},{write_bytes},{rx_bytes},{tx_bytes},{rx_packets},{tx_packets}\n"
                )
                self.write_queue.put(line)

    def collect_loop(self):
        logging.info(
            "Starting cAdvisor collection loop (interval %d seconds).",
            self.interval_seconds,
        )
        while not self.stop_event.is_set():
            try:
                self.collect_once()
            except Exception:
                logging.exception("Failed to collect from cAdvisor")
            # Attesa tra un ciclo e il successivo, interrompibile
            for _ in range(self.interval_seconds):
                if self.stop_event.is_set():
                    break
                time.sleep(1)

        logging.info("Collector loop stopped.")

    def container_queue_writer(self):
        logging.info("Starting CSV writer thread for container stats.")
        os.makedirs(self.output_path, exist_ok=True)
        file_path = os.path.join(self.output_path, "cadvisor_container.csv")

        # Scrittura intestazione
        with open(file_path, "w") as f:
            f.write(
                "collected,timestamp,service,image,cpu_total,memory_usage,memory_limit,"
                "reads,writes,readBytes,writeBytes,rx_bytes,tx_bytes,rx_packets,tx_packets\n"
            )

        # Scrittura delle righe
        with open(file_path, "a") as f:
            while not self.stop_event.is_set():
                try:
                    item = self.write_queue.get(timeout=1.0)
                    f.write(item)
                    f.flush()
                except Empty:
                    continue

            # Svuota la coda in uscita dopo lo stop
            while True:
                try:
                    item = self.write_queue.get_nowait()
                except Empty:
                    break
                else:
                    f.write(item)
                    f.flush()

        logging.info("CSV writer thread for container stats stopped.")

    def start(self):
        self.writer_thread = threading.Thread(
            target=self.container_queue_writer,
            daemon=True,
        )
        self.worker_thread = threading.Thread(
            target=self.collect_loop,
            daemon=True,
        )
        self.writer_thread.start()
        self.worker_thread.start()

    def stop(self):
        logging.info("Stopping collector.")
        self.stop_event.set()
        try:
            # Sblocca eventuali get sul writer
            self.write_queue.put("")
        except Exception:
            pass

    def join(self):
        if self.worker_thread is not None:
            self.worker_thread.join()
        if self.writer_thread is not None:
            self.writer_thread.join()


def parse_args():
    parser = argparse.ArgumentParser(
        description="Simple cAdvisor collector for per container metrics."
    )
    parser.add_argument(
        "--cadvisor",
        required=True,
        help="Hostname or URL for cAdvisor (example: localhost:8080 or http://localhost:8080)",
    )
    parser.add_argument(
        "--output",
        required=True,
        help="Output directory for CSV files.",
    )
    parser.add_argument(
        "--interval",
        type=int,
        default=10,
        help="Collection interval in seconds (default: 10).",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Logging level (default: INFO).",
    )
    return parser.parse_args()


def main():
    args = parse_args()

    logging.basicConfig(
        level=getattr(logging, args.log_level),
        format="%(asctime)s [%(levelname)s] %(message)s",
    )

    collector = CAdvisorCollector(
        cadvisor_address=args.cadvisor,
        output_path=args.output,
        interval_seconds=args.interval,
    )

    collector.start()

    # Gestione di SIGINT / SIGTERM per una chiusura morbida
    def handle_signal(signum, frame):
        logging.info("Received signal %s, stopping collector.", signum)
        collector.stop()

    signal.signal(signal.SIGINT, handle_signal)
    try:
        signal.signal(signal.SIGTERM, handle_signal)
    except AttributeError:
        # SIGTERM potrebbe non essere disponibile su alcune piattaforme
        pass

    # Attende la terminazione dei thread
    collector.join()
    logging.info("Collector terminated cleanly.")


if __name__ == "__main__":
    main()
