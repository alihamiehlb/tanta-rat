const fs = require('fs');
const path = require('path');

class Store {
  constructor(filePath) {
    this.filePath = filePath;
    this.data = {};
    this.load();
  }

  load() {
    try {
      if (fs.existsSync(this.filePath)) {
        const fileContent = fs.readFileSync(this.filePath, 'utf8');
        this.data = JSON.parse(fileContent);
      } else {
        // Ensure directory exists
        const dir = path.dirname(this.filePath);
        if (!fs.existsSync(dir)) {
          fs.mkdirSync(dir, { recursive: true });
        }
        this.save();
      }
    } catch (error) {
      console.error(`Error loading store from ${this.filePath}:`, error.message);
      this.data = {};
    }
  }

  get(key) {
    return this.data[key];
  }

  set(key, value) {
    this.data[key] = value;
    this.save();
  }

  delete(key) {
    if (this.data.hasOwnProperty(key)) {
      delete this.data[key];
      this.save();
    }
  }

  getAll() {
    return this.data;
  }

  save() {
    try {
      fs.writeFileSync(this.filePath, JSON.stringify(this.data, null, 2), 'utf8');
    } catch (error) {
      console.error(`Error saving store to ${this.filePath}:`, error.message);
    }
  }
}

// Data directory
const dataDir = path.join(__dirname, 'data');

// Export instances
const blocklistStore = new Store(path.join(dataDir, 'blocklists.json'));
const settingsStore = new Store(path.join(dataDir, 'settings.json'));
const blockLogStore = new Store(path.join(dataDir, 'block_logs.json'));

module.exports = {
  blocklistStore,
  settingsStore,
  blockLogStore
};
