#include <ziparchive/zip_archive.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <zlib.h>

namespace zip_archive {

Writer::~Writer() = default;
Reader::~Reader() = default;

int32_t Inflate(const Reader& reader, const uint64_t compressed_length,
                const uint64_t uncompressed_length, Writer* writer, uint64_t* crc_out) {
  if (writer == nullptr) return -1;
  std::array<uint8_t, 32768> compressed{};
  std::array<uint8_t, 32768> inflated{};
  z_stream stream{};
  if (inflateInit2(&stream, -MAX_WBITS) != Z_OK) return -1;

  uint64_t compressed_offset = 0;
  uint64_t total_output = 0;
  uLong crc = crc32(0L, Z_NULL, 0);
  int result = Z_OK;
  while (result != Z_STREAM_END && compressed_offset < compressed_length) {
    const auto request = static_cast<size_t>(
        std::min<uint64_t>(compressed.size(), compressed_length - compressed_offset));
    if (!reader.ReadAtOffset(compressed.data(), request, static_cast<off64_t>(compressed_offset))) {
      inflateEnd(&stream);
      return -1;
    }
    compressed_offset += request;
    stream.next_in = compressed.data();
    stream.avail_in = static_cast<uInt>(request);
    do {
      stream.next_out = inflated.data();
      stream.avail_out = static_cast<uInt>(inflated.size());
      result = inflate(&stream, Z_NO_FLUSH);
      if (result != Z_OK && result != Z_STREAM_END) {
        inflateEnd(&stream);
        return -1;
      }
      const size_t produced = inflated.size() - stream.avail_out;
      if (produced > 0) {
        if (produced > uncompressed_length || total_output > uncompressed_length - produced ||
            !writer->Append(inflated.data(), produced)) {
          inflateEnd(&stream);
          return -1;
        }
        total_output += produced;
        crc = crc32(crc, inflated.data(), static_cast<uInt>(produced));
      }
    } while (stream.avail_in > 0 && result != Z_STREAM_END);
  }
  inflateEnd(&stream);
  if (result != Z_STREAM_END || compressed_offset != compressed_length ||
      total_output != uncompressed_length) {
    return -1;
  }
  if (crc_out != nullptr) *crc_out = static_cast<uint64_t>(crc);
  return 0;
}

}  // namespace zip_archive
